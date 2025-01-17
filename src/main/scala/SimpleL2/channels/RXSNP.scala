package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import Utils.{GenerateVerilog, LeakChecker}
import SimpleL2.Configs._
import SimpleL2.Bundles._
import SimpleL2.chi._
import SimpleL2.chi.CHIOpcodeSNP._

class RXSNP()(implicit p: Parameters) extends L2Module {
    val io = IO(new Bundle {
        val rxsnp = Flipped(DecoupledIO(new CHIBundleSNP(chiBundleParams)))
        val task  = DecoupledIO(new TaskBundle)

        val powerStateOpt       = if (hasLowPowerInterface) Some(Input(UInt(PowerState.width.W))) else None
        val sramWakeupFinishOpt = if (hasLowPowerInterface) Some(Input(Bool())) else None
    })

    // Addr in CHI SNP channel has 3 fewer bits than full address
    val snpFullAddr        = Cat(io.rxsnp.bits.addr, 0.U(3.W))
    val (tag, set, offset) = parseAddress(snpFullAddr)

    println(s"[${this.getClass().toString()}] rxsnpHasLatch:${optParam.rxsnpHasLatch}")

    if (!optParam.rxsnpHasLatch) {
        val lowPowerState = io.powerStateOpt.getOrElse(PowerState.ACTIVE)
        val allowReq      = Mux(hasLowPowerInterface.B, io.sramWakeupFinishOpt.getOrElse(true.B) && lowPowerState === PowerState.ACTIVE, true.B)
        io.rxsnp.ready           := io.task.ready && allowReq
        io.task.valid            := io.rxsnp.valid && allowReq
        io.task.bits             := DontCare
        io.task.bits.set         := set
        io.task.bits.tag         := tag
        io.task.bits.channel     := L2Channel.ChannelB
        io.task.bits.opcode      := io.rxsnp.bits.opcode
        io.task.bits.isCHIOpcode := true.B
        io.task.bits.txnID       := io.rxsnp.bits.txnID
        io.task.bits.srcID       := io.rxsnp.bits.srcID
        io.task.bits.retToSrc    := io.rxsnp.bits.retToSrc

        io.task.bits.fwdNID_opt.foreach(_ := io.rxsnp.bits.fwdNID)
        io.task.bits.fwdTxnID_opt.foreach(_ := io.rxsnp.bits.fwdTxnID)
    } else {
        // latch one cycle for better timing

        val task_sn1 = WireInit(0.U.asTypeOf(new TaskBundle))
        val fire_sn1 = WireInit(false.B)

        val full_s0 = RegInit(false.B)
        val task_s0 = RegInit(0.U.asTypeOf(new TaskBundle))

        // -----------------------------------------------------------------------------------------
        // Stage n1
        // -----------------------------------------------------------------------------------------
        fire_sn1             := io.rxsnp.fire
        task_sn1.set         := set
        task_sn1.tag         := tag
        task_sn1.channel     := L2Channel.ChannelB
        task_sn1.isCHIOpcode := true.B
        task_sn1.opcode      := io.rxsnp.bits.opcode
        task_sn1.txnID       := io.rxsnp.bits.txnID
        task_sn1.srcID       := io.rxsnp.bits.srcID
        task_sn1.retToSrc    := io.rxsnp.bits.retToSrc

        task_sn1.fwdNID_opt.foreach(_ := io.rxsnp.bits.fwdNID)
        task_sn1.fwdTxnID_opt.foreach(_ := io.rxsnp.bits.fwdTxnID)

        io.rxsnp.ready := !full_s0 && Mux(hasLowPowerInterface.B, io.sramWakeupFinishOpt.getOrElse(true.B) && io.powerStateOpt.getOrElse(PowerState.ACTIVE) === PowerState.ACTIVE, true.B)

        // -----------------------------------------------------------------------------------------
        // Stage 0
        // -----------------------------------------------------------------------------------------
        when(fire_sn1) {
            task_s0 := task_sn1
            full_s0 := true.B
        }.elsewhen(io.task.fire) {
            full_s0 := false.B
        }

        io.task.valid := full_s0
        io.task.bits  := task_s0

        /**
         * from IHI0050G: P269
         * For Non-forwarding snoops, except SnpMakeInvalid, the rules for returning a copy of the cache line to the Home are:
         *  - Irrespective of the value of RetToSrc, must return a copy if the cache line is Dirty
         *  - Irrespective of the value of RetToSrc, optionally can return a copy if the cache line is Unique Clean([[MixedState.TC]] / [[MixedState.TTC]]).
         *  - If the RetToSrc value is 1, must return a copy if the cache line is Shared Clean([[MixedState.SC]]).
         *  - If the RetToSrc value is 0, must not return a copy if the cache line is Shared Clean([[MixedState.SC]]).
         * For Forwarding snoops where data is being forwarded, the rules for returning a copy of the cache line to the Home are:
         *  - Irrespective of the value of RetToSrc, must return a copy if a Dirty cache line cannot be forwarded or kept.
         *  - If the RetToSrc value is 1, must return a copy if the cache line is Dirty or Clean.
         *  - If the RetToSrc value is 0, must not return a copy if the cache line is Clean
         */
        io.task.bits.retToSrc := task_s0.retToSrc
    }

    if (hasLowPowerInterface) {
        when(io.rxsnp.fire) {
            assert(io.powerStateOpt.get === PowerState.ACTIVE, "RXSNP must be in ACTIVE power state")
        }
    }

    /**
     * from IHI0050G: P269
     * RetToSrc is inapplicable and must be set to 0 in:
     *      SnpCleanShared, SnpCleanInvalid, and SnpMakeInvalid
     *      SnpOnceFwd and SnpUniqueFwd
     *      SnpMakeInvalidStash, SnpStashUnique, and SnpStashShared
     *      SnpQuery
     */
    val opcode         = io.rxsnp.bits.opcode
    val checkOpcodes   = Seq(SnpCleanShared, SnpCleanInvalid, SnpMakeInvalid, SnpOnceFwd, SnpUniqueFwd, SnpMakeInvalidStash, SnpStashUnique, SnpStashShared)
    val opcodeMatchVec = VecInit(Seq.fill(checkOpcodes.length)(opcode).zip(checkOpcodes).map(x => x._1 === x._2)).asUInt
    assert(!(io.rxsnp.fire && opcodeMatchVec.orR && io.rxsnp.bits.retToSrc =/= 0.U), "RetToSrc is inapplicable for this opcode and must be set to 0")

    val implOpcodes        = Seq(SnpShared, SnpUnique, SnpCleanInvalid, SnpNotSharedDirty, SnpMakeInvalid, SnpOnce) ++ { if (supportDCT) Seq(SnpSharedFwd, SnpNotSharedDirtyFwd, SnpUniqueFwd, SnpOnceFwd) else Nil }
    val implOpcodeMatchVec = VecInit(Seq.fill(implOpcodes.length)(opcode).zip(implOpcodes).map(x => x._1 === x._2)).asUInt
    assert(!(io.rxsnp.fire && !implOpcodeMatchVec.orR), "Snp opcode: 0x%x is not implemented", opcode)

    LeakChecker(io.rxsnp.valid, io.rxsnp.fire, Some("RXSNP_valid"), maxCount = deadlockThreshold - 200)
}

object RXSNP extends App {
    val config = SimpleL2.DefaultConfig()

    GenerateVerilog(args, () => new RXSNP()(config), name = "RXSNP", split = false)
}
