package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.ReplacementPolicy
import xs.utils.sram.SRAMTemplate
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import SimpleL2.Configs._
import SimpleL2.Bundles._

object TLState {
    val width   = 2
    val INVALID = "b00".U
    val BRANCH  = "b01".U
    val TRUNK   = "b10".U
    val TIP     = "b11".U
}

object MixedState {
    val width = 4

    //
    // MixedState:
    //      MSB  <--  | Meta[1:0] | Shared | Dirty |  --> LSB
    //
    // Branch Branch: Branch with Shared
    //
    val I   = "b0000".U // Invalid
    val BC  = "b0100".U // Branch Clean
    val BD  = "b0101".U // Branch Dirty
    val BBC = "b0110".U // Branch Branch Clean
    val BBD = "b0111".U // Branch Branch Dirty
    val TTC = "b1010".U // Trunk Clean
    val TTD = "b1011".U // Trunk Dirty
    val TC  = "b1100".U // Tip Clean
    val TD  = "b1101".U // Tip Dirty
}

class MixedState {
    val state = UInt(MixedState.width.W)

    def isDirty() = state(0)
    def isShared() = state(1)
    def isBranch() = state(3, 2) === TLState.BRANCH
    def isTrunk() = state(3, 2) === TLState.TRUNK
    def isTip() = state(3, 2) === TLState.TIP
}

trait HasMixedState {
    val state = UInt(MixedState.width.W)

    def isDirty = state(0)
    def isShared = state(1)
    def isBranch = state(3, 2) === TLState.BRANCH
    def isTrunk = state(3, 2) === TLState.TRUNK
    def isTip = state(3, 2) === TLState.TIP
}

class DirectoryMetaEntry(implicit p: Parameters) extends L2Bundle with HasMixedState {
    val fromPrefetch = Bool()
    val tag          = UInt(tagBits.W)
    val alias        = aliasBitsOpt.map(width => UInt(width.W))
    val clients      = UInt(nrClients.W)
}

class DirRead(implicit p: Parameters) extends L2Bundle {
    val set = UInt(setBits.W)
    val tag = UInt(tagBits.W)
}

class DirWrite(implicit p: Parameters) extends L2Bundle {
    val set   = UInt(setBits.W)
    val meta  = new DirectoryMetaEntry
    val wayOH = UInt(ways.W)
}

class DirResp(implicit p: Parameters) extends L2Bundle {
    val meta  = new DirectoryMetaEntry
    val wayOH = UInt(ways.W)
    val hit   = Bool()
}

class Directory()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val dirRead_s1  = Flipped(Decoupled(new DirRead))
        val dirWrite_s3 = Flipped(Decoupled(new DirWrite))
        val dirResp_s3  = ValidIO(new DirResp)

        // TODO: update replacer SRAM

        val resetFinish = Output(Bool())
    })

    // TODO: ECC

    io <> DontCare

    val resetIdx = RegInit((sets - 1).U)

    val metaArray = Module(
        new SRAMTemplate(
            new DirectoryMetaEntry,
            sets,
            ways,
            singlePort = true,
            hasMbist = false /* TODO */,
            hasShareBus = false /* TDOO */,
            hasClkGate = enableClockGate
            // parentName = parentName + "meta_" /* TODO */
        )
    )

    val repl = ReplacementPolicy.fromString(replacementPolicy, ways)
    
    // @formatter:off
    val replacerSRAM_opt = if (replacementPolicy == "random") None else {
            Some(
                Module(
                    new SRAMTemplate(
                        UInt(repl.nBits.W),
                        sets,
                        1,
                        singlePort = true,
                        shouldReset = true,
                        hasMbist = false /* TODO */,
                        hasShareBus = false /* TODO */,
                        hasClkGate = enableClockGate
                        // parentName = parentName + "repl_"
                    )
                )
            )
        }
    // @formatter:on

    metaArray.io <> DontCare

    // TODO: when should we update replacer SRAM
    replacerSRAM_opt.foreach { sram =>
        sram.io <> DontCare
        dontTouch(sram.io)
    }

    replacerSRAM_opt.foreach { sram =>
        sram.io.w(
            valid = !io.resetFinish,
            data = 0.U, // TODO: replacer SRAM init value
            setIdx = resetIdx,
            waymask = 1.U
        )

        assert(!(!io.resetFinish && sram.io.w.req.valid && !sram.io.w.req.ready))
    }

    // -----------------------------------------------------------------------------------------
    // Stage 1(dir read) / Stage 3(dir write)
    // -----------------------------------------------------------------------------------------
    metaArray.io.r.req.bits.setIdx := io.dirRead_s1.bits.set
    metaArray.io.r.req.valid       := io.dirRead_s1.fire
    metaArray.io.w(
        valid = !io.resetFinish || io.dirWrite_s3.fire,
        data = Mux(io.resetFinish, io.dirWrite_s3.bits.meta, 0.U.asTypeOf(new DirectoryMetaEntry)),
        setIdx = Mux(io.resetFinish, io.dirWrite_s3.bits.set, resetIdx),
        waymask = Mux(io.resetFinish, io.dirWrite_s3.bits.wayOH, Fill(ways, "b1".U))
    )
    io.dirRead_s1.ready := io.resetFinish && metaArray.io.r.req.ready && !io.dirWrite_s3.fire

    io.dirWrite_s3.ready := io.resetFinish && metaArray.io.w.req.ready
    assert(!(io.dirWrite_s3.valid && !metaArray.io.w.req.ready), "dirWrite_s3 while metaArray is not ready!")
    assert(!(io.dirWrite_s3.valid && PopCount(io.dirWrite_s3.bits.wayOH) > 1.U))
    when(!io.resetFinish) {
        assert(!io.dirRead_s1.fire)
        assert(!io.dirWrite_s3.fire)
        assert(!(metaArray.io.w.req.valid && !metaArray.io.w.req.ready))
    }

    // -----------------------------------------------------------------------------------------
    // Stage 2(dir read)
    // -----------------------------------------------------------------------------------------
    val metaRead_s2 = Wire(Vec(ways, new DirectoryMetaEntry()))
    val reqValid_s2 = RegNext(io.dirRead_s1.fire, false.B)
    val reqTag_s2   = RegEnable(io.dirRead_s1.bits.tag, io.dirRead_s1.fire)
    metaRead_s2 := metaArray.io.r.resp.data

    // -----------------------------------------------------------------------------------------
    // Stage 3(dir read)
    // -----------------------------------------------------------------------------------------
    val metaRead_s3 = RegEnable(metaRead_s2, reqValid_s2)
    val reqValid_s3 = RegNext(reqValid_s2, false.B)
    val reqTag_s3   = RegEnable(reqTag_s2, reqValid_s2)
    val stateAll_s3 = metaRead_s3.map(_.state)
    val tagAll_s3   = metaRead_s3.map(_.tag)
    val hitOH_s3 = VecInit(
        stateAll_s3
            .zip(tagAll_s3)
            .map { case (state, tag) =>
                state =/= MixedState.I && tag === reqTag_s3
            }
    ).asUInt
    val hit_s3        = hitOH_s3.asUInt.orR
    val finalWayOH_s3 = Mux(hit_s3, hitOH_s3, UIntToOH(random.LFSR(3)) /* TODO */ )

    when(io.resetFinish) {
        assert(PopCount(hitOH_s3) <= 1.U)
        assert(PopCount(finalWayOH_s3) <= 1.U)
    }

    io.dirResp_s3.valid      := reqValid_s3
    io.dirResp_s3.bits.hit   := hit_s3
    io.dirResp_s3.bits.meta  := Mux1H(finalWayOH_s3, metaRead_s3)
    io.dirResp_s3.bits.wayOH := finalWayOH_s3

    //
    // reset all sram data when reset
    //
    when(!io.resetFinish) {
        resetIdx := resetIdx - 1.U
    }
    io.resetFinish := resetIdx === 0.U && !reset.asBool

    dontTouch(metaArray.io)
    dontTouch(hitOH_s3)
    dontTouch(io)
}

object Directory extends App {
    val config = new Config((_, _, _) => {
        case L2ParamKey      => L2Param()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new Directory()(config), name = "Directory", split = true)
}
