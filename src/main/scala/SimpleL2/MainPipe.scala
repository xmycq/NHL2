package SimpleL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._
import xs.utils.perf.{HasPerfLogging, DebugOptionsKey}
import Utils.{GenerateVerilog, MultiDontTouch}
import SimpleL2.Configs._
import SimpleL2.Bundles._
import SimpleL2.chi._
import SimpleL2.chi.CHIOpcodeDAT._
import SimpleL2.chi.CHIOpcodeSNP._
import SimpleL2.chi.CHIOpcodeRSP._

class MpStageInfo(implicit p: Parameters) extends L2Bundle {
    val valid    = Bool()
    val isRefill = Bool()
    val set      = UInt(setBits.W)
    val tag      = UInt(tagBits.W)
}

class MpStatus4567()(implicit p: Parameters) extends L2Bundle {
    val stage4 = new MpStageInfo
    val stage5 = new MpStageInfo
    val stage6 = new MpStageInfo
    val stage7 = new MpStageInfo
}

class MpMshrRetryTasks()(implicit p: Parameters) extends L2Bundle {
    val mshrId_s2 = Output(UInt(mshrBits.W))
    val stage2    = ValidIO(new MshrRetryStage2)

    val mshrId_s4 = Output(UInt(mshrBits.W))
    val stage4    = ValidIO(new MshrRetryStage4)
}

class MainPipe()(implicit p: Parameters) extends L2Module with HasPerfLogging {
    val io = IO(new Bundle {

        /** Stage 2 */
        val reqDrop_s2_opt     = if (optParam.mshrStallOnReqArb) None else Some(Input(Bool()))
        val mpReq_s2           = Flipped(ValidIO(new TaskBundle))
        val sourceD_s2         = Decoupled(new TaskBundle)
        val txdat_s2           = DecoupledIO(new CHIBundleDAT(chiBundleParams))
        val mshrEarlyNested_s2 = Output(new MshrEarlyNested)

        /** Stage 3 */
        val dirResp_s3    = Flipped(ValidIO(new DirResp))
        val replResp_s3   = Flipped(ValidIO(new DirReplResp))
        val dirWrite_s3   = ValidIO(new DirWrite)
        val mshrAlloc_s3  = DecoupledIO(new MshrAllocBundle)
        val mshrFreeOH_s3 = Input(UInt(nrMSHR.W))
        val mshrNested_s3 = Output(new MshrNestedWriteback)
        val toDS = new Bundle {
            val dsRead_s3    = ValidIO(new DSRead)
            val mshrId_s3    = Output(UInt(mshrBits.W))
            val dsWrWayOH_s3 = ValidIO(UInt(ways.W))
        }

        /** Stage 4 */
        val replayOpt_s4        = if (optParam.sinkaStallOnReqArb) Some(ValidIO(new ReplayRequest)) else None
        val snpBufReplay_s4     = ValidIO(new SnpBufReplay)
        val reqBufReplay_s4_opt = if (!optParam.sinkaStallOnReqArb) Some(ValidIO(new ReqBufReplay)) else None
        val allocDestSinkC_s4   = ValidIO(new RespDataDestSinkC)                 // Alloc SinkC resps(ProbeAckData) data destination, ProbeAckData can be either saved into DataStorage or TempDataStorage
        val sourceD_s4          = DecoupledIO(new TaskBundle)                    // SourceD for non-data resp
        val txrsp_s4            = DecoupledIO(new CHIBundleRSP(chiBundleParams)) // Snp* hit and does not require data will be sent to txrsp_s4

        /** Stage 6 & Stage 7*/
        val sourceD_s6s7 = DecoupledIO(new TaskBundle) // Acquire* hit will send SourceD resp
        val txdat_s6s7   = DecoupledIO(new CHIBundleDAT(chiBundleParams))

        /** Other status signals */
        val status           = Output(new MpStatus4567) // to SoruceB + ReqArb
        val retryTasks       = new MpMshrRetryTasks
        val nonDataRespCnt   = Input(UInt(log2Ceil(nrNonDataSourceDEntry + 1).W))
        val txrspCnt         = Input(UInt(log2Ceil(nrTXRSPEntry + 1).W))
        val prefetchTrainOpt = if (enablePrefetch) Some(DecoupledIO(new SimpleL2.prefetch.PrefetchTrain())) else None
    })

    io <> DontCare

    val ready_s7             = WireInit(false.B)
    val hasValidDataBuf_s6s7 = WireInit(false.B)

    // -----------------------------------------------------------------------------------------
    // Stage 2
    // -----------------------------------------------------------------------------------------
    val task_s2  = WireInit(0.U.asTypeOf(new TaskBundle))
    val valid_s2 = WireInit(false.B)

    valid_s2 := io.mpReq_s2.valid
    task_s2  := io.mpReq_s2.bits

    val isMshrSourceD_resp_s2 = (task_s2.opcode === Grant || task_s2.opcode === AccessAck || task_s2.opcode === ReleaseAck || task_s2.opcode === HintAck) && !task_s2.readTempDs
    val isMshrSourceD_data_s2 = (task_s2.opcode === GrantData || task_s2.opcode === AccessAckData) && task_s2.readTempDs
    val isMshrSourceD_s2      = !task_s2.isCHIOpcode && task_s2.isMshrTask && !task_s2.isReplTask && (isMshrSourceD_resp_s2 || isMshrSourceD_data_s2)
    val isSourceD_s2          = !task_s2.isCHIOpcode && !task_s2.isMshrTask && task_s2.isChannelC && (task_s2.opcode === Release || task_s2.opcode === ReleaseData)
    val sourceD_s2            = WireInit(0.U.asTypeOf(Valid(new TaskBundle)))
    if (enableDataECC) {
        sourceD_s2.valid := valid_s2 && !io.reqDrop_s2_opt.getOrElse(false.B) && (isMshrSourceD_s2 || isSourceD_s2)
        sourceD_s2.bits  := task_s2
        when(isSourceD_s2) {
            sourceD_s2.bits.opcode := ReleaseAck
        }
    } else {
        io.sourceD_s2.valid := valid_s2 && !io.reqDrop_s2_opt.getOrElse(false.B) && (isMshrSourceD_s2 || isSourceD_s2)
        io.sourceD_s2.bits  <> task_s2
        when(isSourceD_s2) {
            io.sourceD_s2.bits.opcode := ReleaseAck
            assert(!(io.sourceD_s2.valid && !io.sourceD_s2.ready), "SourceD_s2 should not be blocked")
        }
    }

    val isSnpToN_s2    = CHIOpcodeSNP.isSnpToN(task_s2.opcode) && task_s2.isChannelB
    val isSnpToB_s2    = CHIOpcodeSNP.isSnpToB(task_s2.opcode) && task_s2.isChannelB
    val isSnpFwd_s2    = CHIOpcodeSNP.isSnpXFwd(task_s2.opcode) && task_s2.isChannelB && supportDCT.B
    val isMshrTXDAT_s2 = task_s2.isMshrTask && !task_s2.isReplTask && task_s2.isCHIOpcode && task_s2.readTempDs && task_s2.channel === CHIChannel.TXDAT
    val isCompData_s2  = isMshrTXDAT_s2 && task_s2.opcode === CompData && supportDCT.B
    val isTXDAT_s2     = task_s2.isChannelB && task_s2.readTempDs && task_s2.snpHitReq && !isSnpFwd_s2
    val snpNeedMshr_s2 = isTXDAT_s2 && io.txdat_s2.valid && !io.txdat_s2.ready // If txdat is not ready, we should let this req enter mshr, the mshrId is task_s2.snpHitMshrId
    val txdat_s2       = WireInit(0.U.asTypeOf(Valid(new CHIBundleDAT(chiBundleParams))))
    if (enableDataECC) {
        txdat_s2.valid        := valid_s2 && !io.reqDrop_s2_opt.getOrElse(false.B) && (isMshrTXDAT_s2 || isTXDAT_s2)
        txdat_s2.bits         := DontCare
        txdat_s2.bits.tgtID   := Mux(isTXDAT_s2, task_s2.srcID, task_s2.tgtID)
        txdat_s2.bits.txnID   := task_s2.txnID
        txdat_s2.bits.homeNID := task_s2.srcID
        txdat_s2.bits.dbID    := Mux(isCompData_s2, task_s2.dbID, Mux(task_s2.snpHitReq, task_s2.snpHitMshrId, task_s2.mshrId))
        txdat_s2.bits.resp    := Mux(task_s2.snpHitReq, Mux(task_s2.snpGotDirty, Mux(isSnpToN_s2, Resp.I_PD, Resp.SC_PD), Mux(isSnpToN_s2, Resp.I, Resp.SC)), task_s2.resp)
        txdat_s2.bits.be      := Fill(beatBytes, 1.U)
        txdat_s2.bits.opcode  := Mux(task_s2.snpHitReq, SnpRespData, task_s2.opcode)
    } else {
        io.txdat_s2.valid        := valid_s2 && !io.reqDrop_s2_opt.getOrElse(false.B) && (isMshrTXDAT_s2 || isTXDAT_s2)
        io.txdat_s2.bits         := DontCare
        io.txdat_s2.bits.tgtID   := Mux(isTXDAT_s2, task_s2.srcID, task_s2.tgtID)
        io.txdat_s2.bits.txnID   := task_s2.txnID
        io.txdat_s2.bits.homeNID := task_s2.srcID
        io.txdat_s2.bits.dbID    := Mux(isCompData_s2, task_s2.dbID, Mux(task_s2.snpHitReq, task_s2.snpHitMshrId, task_s2.mshrId))
        io.txdat_s2.bits.resp    := Mux(task_s2.snpHitReq, Mux(task_s2.snpGotDirty, Mux(isSnpToN_s2, Resp.I_PD, Resp.SC_PD), Mux(isSnpToN_s2, Resp.I, Resp.SC)), task_s2.resp)
        io.txdat_s2.bits.be      := Fill(beatBytes, 1.U)
        io.txdat_s2.bits.opcode  := Mux(task_s2.snpHitReq, SnpRespData, task_s2.opcode)
    }

    val dropMshrTask_s2 = if (optParam.mshrStallOnReqArb) {
        false.B
    } else {
        task_s2.isMshrTask && !task_s2.isCHIOpcode && !task_s2.isReplTask && io.reqDrop_s2_opt.getOrElse(false.B)
    }
    val sourcedStall_s2 = io.sourceD_s2.valid && !io.sourceD_s2.ready
    val txdatStall_s2   = io.txdat_s2.valid && !io.txdat_s2.ready
    val hasRetry_s2     = io.retryTasks.stage2.valid && io.retryTasks.stage2.bits.isRetry_s2
    val retryTasks_s2   = WireInit(0.U.asTypeOf(chiselTypeOf(io.retryTasks)))
    if (enableDataECC) {
        retryTasks_s2.mshrId_s2                := task_s2.mshrId
        retryTasks_s2.stage2.valid             := (isMshrSourceD_s2 || isMshrTXDAT_s2 || dropMshrTask_s2) && valid_s2
        retryTasks_s2.stage2.bits.isRetry_s2   := sourcedStall_s2 || txdatStall_s2 || dropMshrTask_s2
        retryTasks_s2.stage2.bits.grant_s2     := !task_s2.isCHIOpcode && (task_s2.opcode === Grant || task_s2.opcode === GrantData)
        retryTasks_s2.stage2.bits.accessack_s2 := !task_s2.isCHIOpcode && (task_s2.opcode === AccessAck || task_s2.opcode === AccessAckData)
        retryTasks_s2.stage2.bits.cbwrdata_s2  := task_s2.isCHIOpcode && (task_s2.opcode === CopyBackWrData) // TODO: remove this since CopyBackWrData will be handled in stage 6 or stage 7
        retryTasks_s2.stage2.bits.snpresp_s2   := task_s2.isCHIOpcode && (task_s2.opcode === SnpRespData)
        retryTasks_s2.stage2.bits.compdat_opt_s2.foreach(_ := task_s2.isCHIOpcode && (task_s2.opcode === CompData))

        assert(
            !(!task_s2.isCHIOpcode && task_s2.opcode === HintAck && retryTasks_s2.stage2.valid && retryTasks_s2.stage2.bits.isRetry_s2),
            "HintAck should not trigger retryTasks_s2"
        )
    } else {
        io.retryTasks.mshrId_s2                := task_s2.mshrId
        io.retryTasks.stage2.valid             := (isMshrSourceD_s2 || isMshrTXDAT_s2 || dropMshrTask_s2) && valid_s2
        io.retryTasks.stage2.bits.isRetry_s2   := sourcedStall_s2 || txdatStall_s2 || dropMshrTask_s2
        io.retryTasks.stage2.bits.grant_s2     := !task_s2.isCHIOpcode && (task_s2.opcode === Grant || task_s2.opcode === GrantData)
        io.retryTasks.stage2.bits.accessack_s2 := !task_s2.isCHIOpcode && (task_s2.opcode === AccessAck || task_s2.opcode === AccessAckData)
        io.retryTasks.stage2.bits.cbwrdata_s2  := task_s2.isCHIOpcode && (task_s2.opcode === CopyBackWrData) // TODO: remove this since CopyBackWrData will be handled in stage 6 or stage 7
        io.retryTasks.stage2.bits.snpresp_s2   := task_s2.isCHIOpcode && (task_s2.opcode === SnpRespData)
        io.retryTasks.stage2.bits.compdat_opt_s2.foreach(_ := task_s2.isCHIOpcode && (task_s2.opcode === CompData))

        assert(
            !(!task_s2.isCHIOpcode && task_s2.opcode === HintAck && io.retryTasks.stage2.valid && io.retryTasks.stage2.bits.isRetry_s2),
            "HintAck should not trigger io.retryTasks"
        )
    }

    /**
      * Notify [[MSHR]]s that the stage 2 task may nested [[MSHR]] in stage 3.
      * This is used to optimize the timing path between nested write-back at [[MainPipe]] stage 3 and the txreq task blocking logic in [[MSHR]].
      */
    io.mshrEarlyNested_s2.set       := task_s2.set
    io.mshrEarlyNested_s2.tag       := task_s2.tag
    io.mshrEarlyNested_s2.isMshr    := task_s2.isMshrTask
    io.mshrEarlyNested_s2.isSnpToN  := valid_s2 && ((task_s2.isChannelB && isSnpToN_s2 && Mux(enableDataECC.B, true.B, !snpNeedMshr_s2)) || task_s2.isMshrTask && task_s2.updateDir && task_s2.newMetaEntry.state === MixedState.I)
    io.mshrEarlyNested_s2.isRelease := valid_s2 && task_s2.isChannelC && task_s2.opcode === ReleaseData

    // -----------------------------------------------------------------------------------------
    // Stage 2 ecc
    // -----------------------------------------------------------------------------------------
    val valid_s2e      = RegNext(valid_s2, false.B)
    val retryTasks_s2e = RegEnable(retryTasks_s2, valid_s2)
    val sourceD_s2e    = RegEnable(sourceD_s2, valid_s2)
    val txdat_s2e      = RegEnable(txdat_s2, valid_s2)

    val isSourceD_s2e = RegEnable(isSourceD_s2, valid_s2)
    if (enableDataECC) {
        io.sourceD_s2.valid := sourceD_s2e.valid && valid_s2e
        io.sourceD_s2.bits  := sourceD_s2e.bits
        when(isSourceD_s2e) {
            assert(!(io.sourceD_s2.valid && !io.sourceD_s2.ready), "SourceD_s2 at stage2e should not be blocked")
        }
    }

    val isTXDAT_s2e     = RegEnable(isTXDAT_s2, valid_s2)
    val snpNeedMshr_s2e = isTXDAT_s2e && io.txdat_s2.valid && !io.txdat_s2.ready // If txdat is not ready, we should let this req enter mshr, the mshrId is task_s2.snpHitMshrId
    if (enableDataECC) {
        io.txdat_s2.valid := txdat_s2e.valid && valid_s2e
        io.txdat_s2.bits  := txdat_s2e.bits
    }

    val dropMshrTask_s2e = RegEnable(dropMshrTask_s2, valid_s2)
    val sourcedStall_s2e = io.sourceD_s2.valid && !io.sourceD_s2.ready
    val txdatStall_s2e   = io.txdat_s2.valid && !io.txdat_s2.ready
    val hasRetry_s2e     = io.retryTasks.stage2.valid && io.retryTasks.stage2.bits.isRetry_s2
    if (enableDataECC) {
        io.retryTasks                        <> retryTasks_s2e
        io.retryTasks.stage2.valid           := retryTasks_s2e.stage2.valid && valid_s2e
        io.retryTasks.stage2.bits.isRetry_s2 := sourcedStall_s2e || txdatStall_s2e || dropMshrTask_s2e
    }

    // -----------------------------------------------------------------------------------------
    // Stage 3
    // -----------------------------------------------------------------------------------------
    val dirResp_s3     = io.dirResp_s3.bits
    val task_s3        = RegEnable(task_s2, 0.U.asTypeOf(new TaskBundle), valid_s2)
    val hasRetry_s3    = if (enableDataECC) hasRetry_s2e else RegEnable(hasRetry_s2, valid_s2)
    val valid_s3       = RegNext(valid_s2 && !io.reqDrop_s2_opt.getOrElse(false.B), false.B)
    val isSnpHitReq_s3 = task_s3.snpHitReq && valid_s3
    val hit_s3         = dirResp_s3.hit && io.dirResp_s3.valid // && !isSnpHitReq_s3 // hit is invalid for snpHitReq request
    val meta_s3        = dirResp_s3.meta
    val state_s3       = meta_s3.state
    assert(!(valid_s3 && !task_s3.isMshrTask && !task_s3.snpHitReq && !io.dirResp_s3.fire), "Directory response should be valid!")

    val snpNeedMshr_s3        = if (enableDataECC) snpNeedMshr_s2e else RegEnable(snpNeedMshr_s2, valid_s2)
    val reqNeedT_s3           = needT(task_s3.opcode, task_s3.param)
    val reqClientOH_s3        = getClientBitOH(task_s3.source)
    val noClients_s3          = !meta_s3.clientsOH.orR
    val hasOtherClients_s3    = (meta_s3.clientsOH & ~reqClientOH_s3).orR
    val isReqClient_s3        = (reqClientOH_s3 & meta_s3.clientsOH).orR // whether the request is from the same client
    val sinkRespPromoteT_a_s3 = hit_s3 && meta_s3.isTip && noClients_s3

    val isGet_s3          = task_s3.opcode === Get && task_s3.isChannelA
    val isAcquireBlock_s3 = task_s3.opcode === AcquireBlock && task_s3.isChannelA
    val isAcquirePerm_s3  = task_s3.opcode === AcquirePerm && task_s3.isChannelA
    val isAcquire_s3      = isAcquireBlock_s3 || isAcquirePerm_s3
    val isPrefetch_s3     = task_s3.opcode === Hint && task_s3.isChannelA
    val cacheAlias_s3     = isAcquire_s3 && hit_s3 && isReqClient_s3 && meta_s3.aliasOpt.getOrElse(0.U) =/= task_s3.aliasOpt.getOrElse(0.U)
    val isSnpToN_s3       = CHIOpcodeSNP.isSnpToN(task_s3.opcode) && task_s3.isChannelB
    val isSnpToB_s3       = CHIOpcodeSNP.isSnpToB(task_s3.opcode) && task_s3.isChannelB
    val isSnpOnceX_s3     = CHIOpcodeSNP.isSnpOnceX(task_s3.opcode) && task_s3.isChannelB
    val isSnoop_s3        = isSnpToB_s3 || isSnpToN_s3 || isSnpOnceX_s3
    val isFwdSnoop_s3     = CHIOpcodeSNP.isSnpXFwd(task_s3.opcode) && supportDCT.B
    val isReleaseData_s3  = task_s3.opcode === ReleaseData && task_s3.isChannelC
    val isRelease_s3      = task_s3.opcode === Release && task_s3.isChannelC

    val mpTask_refill_s3  = valid_s3 && task_s3.isMshrTask && !task_s3.isCHIOpcode && !task_s3.readTempDs && (task_s3.opcode === AccessAckData || task_s3.opcode === GrantData)
    val mpTask_snpresp_s3 = valid_s3 && task_s3.isMshrTask && task_s3.isCHIOpcode && (task_s3.opcode === SnpResp || task_s3.opcode === SnpRespData)

    val needReadOnMiss_a_s3   = !hit_s3 && (isGet_s3 || isAcquire_s3 || isPrefetch_s3)
    val needReadOnHit_a_s3    = hit_s3 && (!isPrefetch_s3 && reqNeedT_s3 && meta_s3.isBranch) // send MakeUnique. Notice: Prefetch and hit while permission is not enough will not lead to permission upgrade.
    val needReadDownward_a_s3 = task_s3.isChannelA && (needReadOnHit_a_s3 || needReadOnMiss_a_s3)
    val needProbeOnHit_a_s3 = if (nrClients > 1) {
        isGet_s3 && hit_s3 && meta_s3.isTrunk ||
        isAcquire_s3 && hit_s3 && Mux(
            reqNeedT_s3,

            /** Acquire.NtoT / Acquire.BtoT */
            meta_s3.isTrunk || (meta_s3.isTip || meta_s3.isBranch) && hasOtherClients_s3,

            /** Acquire.NtoB */
            meta_s3.isTrunk
        )
    } else {
        isGet_s3 && hit_s3 && meta_s3.isTrunk || isAcquire_s3 && hit_s3 && cacheAlias_s3 && meta_s3.clientsOH.orR
    }
    val needProbeOnMiss_a_s3 = task_s3.isChannelA && !hit_s3 && meta_s3.clientsOH.orR
    val needProbe_a_s3       = task_s3.isChannelA && (hit_s3 && needProbeOnHit_a_s3 || !hit_s3 && needProbeOnMiss_a_s3)

    val needProbe_snpOnceX_s3 = CHIOpcodeSNP.isSnpOnceX(task_s3.opcode) && dirResp_s3.hit && meta_s3.isTrunk
    val needProbe_snpToB_s3   = (CHIOpcodeSNP.isSnpToB(task_s3.opcode) || task_s3.opcode === SnpCleanShared) && dirResp_s3.hit && meta_s3.isTrunk
    val needProbe_snpToN_s3   = (CHIOpcodeSNP.isSnpUniqueX(task_s3.opcode) || CHIOpcodeSNP.isSnpMakeInvalidX(task_s3.opcode) || task_s3.opcode === SnpCleanInvalid) && dirResp_s3.hit && meta_s3.clientsOH.orR
    val needProbe_b_s3        = task_s3.isChannelB && hit_s3 && (needProbe_snpOnceX_s3 || needProbe_snpToB_s3 || needProbe_snpToN_s3)
    val needDCT_s3            = isFwdSnoop_s3 && hit_s3 && supportDCT.B

    val canAllocMshr_s3 = !task_s3.isMshrTask && valid_s3
    val mshrRealloc_s3  = (snpNeedMshr_s3 || isFwdSnoop_s3) && task_s3.snpHitReq && canAllocMshr_s3
    val mshrAlloc_a_s3  = (needReadDownward_a_s3 || needProbe_a_s3 || cacheAlias_s3) && canAllocMshr_s3
    val mshrAlloc_b_s3  = ((needProbe_b_s3 || needDCT_s3) && !task_s3.snpHitWriteBack && !task_s3.snpHitReq) && canAllocMshr_s3 // if snoop hit mshr that is scheduling writeback(MSHR_A), we should not update directory since MSHR_A will overwrite the whole cacheline
    val mshrAlloc_c_s3  = false.B                                                                                               // for inclusive cache, Release/ReleaseData always hit
    val mshrAlloc_lp_s3 = task_s3.isLowPowerTaskOpt.getOrElse(false.B) && dirResp_s3.hit
    val mshrAlloc_s3    = mshrAlloc_a_s3 || mshrAlloc_b_s3 || mshrAlloc_c_s3 || mshrRealloc_s3 || mshrAlloc_lp_s3

    val mshrAllocStates = WireInit(0.U.asTypeOf(new MshrFsmState))
    mshrAllocStates.elements.foreach(_._2 := true.B)
    when(task_s3.isChannelA) {

        /** need to send replTask to [[Directory]] */
        mshrAllocStates.s_repl     := dirResp_s3.hit || !dirResp_s3.hit && dirResp_s3.meta.isInvalid && !dirResp_s3.needsRepl
        mshrAllocStates.w_replResp := dirResp_s3.hit || !dirResp_s3.hit && dirResp_s3.meta.isInvalid && !dirResp_s3.needsRepl

        when(isGet_s3) {
            mshrAllocStates.s_accessack      := false.B
            mshrAllocStates.w_accessack_sent := false.B
        }

        when(isAcquire_s3) {
            mshrAllocStates.s_grant      := false.B
            mshrAllocStates.w_grant_sent := false.B
            mshrAllocStates.w_grantack   := false.B
        }

        when(isPrefetch_s3) {
            mshrAllocStates.s_hintack := false.B
        }

        when(needReadDownward_a_s3) {
            when(isAcquirePerm_s3) {
                mshrAllocStates.s_makeunique := false.B
                mshrAllocStates.w_comp       := false.B
                mshrAllocStates.s_compack    := false.B
            }.otherwise {
                when(valid_s3) {
                    assert(isAcquireBlock_s3 || isGet_s3 || isPrefetch_s3, "opcode:%x channel:%x", task_s3.opcode, task_s3.channel)
                }

                mshrAllocStates.s_read          := false.B
                mshrAllocStates.w_compdat       := false.B
                mshrAllocStates.w_compdat_first := false.B
                mshrAllocStates.s_compack       := false.B

                // Also support seperate response and data
                mshrAllocStates.w_respsepdata       := false.B
                mshrAllocStates.w_datasepresp       := false.B
                mshrAllocStates.w_datasepresp_first := false.B
            }
        }

        when(cacheAlias_s3 || needProbeOnHit_a_s3) {
            mshrAllocStates.s_aprobe          := false.B
            mshrAllocStates.w_aprobeack       := false.B
            mshrAllocStates.w_aprobeack_first := false.B
        }

        // when(needProbeOnMiss_a_s3) {
        //     mshrAllocStates.s_rprobe          := false.B
        //     mshrAllocStates.w_rprobeack       := false.B
        //     mshrAllocStates.w_rprobeack_first := false.B
        // }
    }

    when(task_s3.isChannelB) {
        mshrAllocStates.s_snpresp      := false.B
        mshrAllocStates.w_snpresp_sent := false.B

        when(needProbe_b_s3) {
            mshrAllocStates.s_sprobe          := false.B
            mshrAllocStates.w_sprobeack       := false.B
            mshrAllocStates.w_sprobeack_first := false.B
        }

        when(needDCT_s3) {
            mshrAllocStates.s_compdat      := false.B
            mshrAllocStates.w_compdat_sent := false.B
        }
    }

    when(task_s3.isChannelC) {
        // TODO: Release should always hit
    }

    when(task_s3.isLowPowerTaskOpt.getOrElse(false.B)) {
        when(dirResp_s3.meta.isDirty) {
            mshrAllocStates.s_wb            := false.B
            mshrAllocStates.w_compdbid      := false.B
            mshrAllocStates.s_cbwrdata      := false.B
            mshrAllocStates.w_cbwrdata_sent := false.B
        }.otherwise {
            mshrAllocStates.s_evict      := false.B
            mshrAllocStates.w_evict_comp := false.B
        }

        when(dirResp_s3.meta.clientsOH.orR) {
            mshrAllocStates.s_rprobe          := false.B
            mshrAllocStates.w_rprobeack       := false.B
            mshrAllocStates.w_rprobeack_first := false.B
        }
    }

    val mshrReallocStates_s3 = WireInit(0.U.asTypeOf(new MshrFsmState))
    mshrReallocStates_s3.elements.foreach(_._2 := true.B)
    when(task_s3.isChannelB) {
        mshrReallocStates_s3.s_snpresp      := false.B
        mshrReallocStates_s3.w_snpresp_sent := false.B

        when(isFwdSnoop_s3) {
            mshrReallocStates_s3.s_compdat      := false.B
            mshrReallocStates_s3.w_compdat_sent := false.B
        }
    }

    val mshrReplay_s3    = io.mshrAlloc_s3.valid && !io.mshrAlloc_s3.ready && valid_s3
    val reallocMshrId_s3 = task_s3.snpHitMshrId
    io.mshrAlloc_s3.valid                := mshrAlloc_s3
    io.mshrAlloc_s3.bits.mshrId          := Mux(mshrRealloc_s3, reallocMshrId_s3, OHToUInt(io.mshrFreeOH_s3))
    io.mshrAlloc_s3.bits.dirResp         := dirResp_s3
    io.mshrAlloc_s3.bits.fsmState        := Mux(mshrRealloc_s3, mshrReallocStates_s3, mshrAllocStates)
    io.mshrAlloc_s3.bits.req             := task_s3
    io.mshrAlloc_s3.bits.req.isAliasTask := cacheAlias_s3
    io.mshrAlloc_s3.bits.realloc         := mshrRealloc_s3
    io.mshrAlloc_s3.bits.snpGotDirty     := task_s3.snpGotDirty
    assert(!(task_s3.isLowPowerTaskOpt.getOrElse(false.B) && io.mshrAlloc_s3.valid && !io.mshrAlloc_s3.ready), "low power task should always valid to alloc mshr!")

    val snpReplay_dup_s3 = WireInit(false.B)
    io.mshrNested_s3                  <> DontCare
    io.mshrNested_s3.isMshr           := task_s3.isMshrTask
    io.mshrNested_s3.mshrId           := task_s3.mshrId
    io.mshrNested_s3.set              := task_s3.set
    io.mshrNested_s3.tag              := task_s3.tag
    io.mshrNested_s3.source           := task_s3.source
    io.mshrNested_s3.snoop.cleanDirty := io.mshrNested_s3.snoop.toN || io.mshrNested_s3.snoop.toB
    io.mshrNested_s3.snoop.toN := (isSnpToN_s3 && ((!mshrAlloc_b_s3 && hit_s3) || task_s3.snpHitReq && Mux(
        mshrRealloc_s3,
        io.mshrAlloc_s3.ready,
        true.B
    )) && !snpReplay_dup_s3 || task_s3.isMshrTask && (task_s3.channel === L2Channel.TXDAT || task_s3.channel === L2Channel.TXRSP) && task_s3.updateDir && task_s3.newMetaEntry.state === MixedState.I) && valid_s3
    io.mshrNested_s3.snoop.toB := (isSnpToB_s3 && ((!mshrAlloc_b_s3 && hit_s3) || task_s3.snpHitReq && Mux(
        mshrRealloc_s3,
        io.mshrAlloc_s3.ready,
        true.B
    )) && !snpReplay_dup_s3 || task_s3.isMshrTask && (task_s3.channel === L2Channel.TXDAT || task_s3.channel === L2Channel.TXRSP) && task_s3.updateDir && task_s3.newMetaEntry.state === MixedState.BC) && valid_s3
    io.mshrNested_s3.release.setDirty := task_s3.isChannelC && task_s3.opcode === ReleaseData && task_s3.param === TtoN && valid_s3
    io.mshrNested_s3.release.TtoN     := (isRelease_s3 || isReleaseData_s3) && task_s3.param === TtoN && valid_s3
    io.mshrNested_s3.release.BtoN     := (isRelease_s3 || isReleaseData_s3) && task_s3.param === BtoN && valid_s3

    /** coherency check */
    when(valid_s3) {
        val addr = Cat(task_s3.tag, task_s3.set, 0.U(6.W))
        assert(!(io.dirResp_s3.fire && dirResp_s3.hit && state_s3 === MixedState.I), "Hit on INVALID state! addr:%x", addr)
        assert(!(io.dirResp_s3.fire && meta_s3.isTrunk && !dirResp_s3.meta.clientsOH.orR), "Trunk should have clientsOH! addr:%x", addr)
        assert(!(io.dirResp_s3.fire && meta_s3.isTrunk && PopCount(dirResp_s3.meta.clientsOH) > 1.U), "Trunk should have only one client! addr:%x", addr)

        // ! It should be fine that Acquire.BtoT missed in L2Cache. It happens on a Probe nested upstream cache while it is already issued Acquire.BtoT.
        // assert(!(task_s3.isChannelA && isAcquire_s3 && task_s3.param === BtoT && hit_s3 && !isReqClient_s3), "Acquire.BtoT should have clientsOH! addr:%x", addr)

        assert(
            !(task_s3.isChannelA && isAcquire_s3 && task_s3.param === NtoB && dirResp_s3.hit && isReqClient_s3 && meta_s3.isTrunk && !cacheAlias_s3),
            "Acquire.NtoB should never get trunk state! addr:%x", // It is possible to get trunk state for cacheAlias_s3.
            addr
        )

        assert(!(task_s3.isChannelA && isAcquirePerm_s3 && task_s3.param === NtoB), "Unsupported AcquirePerm.NtoB! addr:%x", addr)

        // assert(!(task_s3.isChannelC && (isRelease_s3 || isReleaseData_s3) && !dirResp_s3.hit), "Release/ReleaseData should always hit! addr => TODO: ")
        assert(!((isRelease_s3 || isReleaseData_s3) && task_s3.param === TtoB && task_s3.opcode =/= Release), "TtoB can only be used in Release") // TODO: ReleaseData.TtoB
        assert(!((isRelease_s3 || isReleaseData_s3) && task_s3.param === NtoN), "Unsupported Release.NtoN")

        assert(!(io.dirWrite_s3.fire && io.dirWrite_s3.bits.meta.isBranch && io.dirWrite_s3.bits.meta.isDirty), "Branch should never be dirty! addr:%x", addr)
    }

    /** Deal with snoop requests */
    val txrspWillFull_s3 = io.txrspCnt >= (nrTXRSPEntry - 1).U
    val snpNeedData_b_s3 = !task_s3.isMshrTask && task_s3.isChannelB && hit_s3 && Mux(
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
        isFwdSnoop_s3,
        task_s3.retToSrc || !task_s3.retToSrc && meta_s3.isDirty || task_s3.snpGotDirty /* snpHitWriteBack needs to read Directory but snpHitReq does not */,
        (meta_s3.isDirty || task_s3.retToSrc || !task_s3.retToSrc && meta_s3.isDirty || task_s3.snpGotDirty) && !CHIOpcodeSNP.isSnpMakeInvalidX(task_s3.opcode)
    )
    val snpNeedData_mshr_s3 = mpTask_snpresp_s3 && !task_s3.readTempDs && task_s3.channel === CHIChannel.TXDAT
    val snpChnlReqOK_s3     = !task_s3.isMshrTask && !(task_s3.snpHitReq && task_s3.readTempDs) && isSnoop_s3 && task_s3.isChannelB && !mshrAlloc_b_s3 && !mshrRealloc_s3 && valid_s3 // Can ack snoop request without allocating MSHR, Snoop miss did not need mshr, response with SnpResp_I
    val snpReplay_s3        = task_s3.isChannelB && !mshrAlloc_b_s3 && (!snpNeedData_b_s3 && txrspWillFull_s3 || snpNeedData_b_s3 && !hasValidDataBuf_s6s7) && valid_s3
    val snpRetry_s3         = task_s3.isMshrTask && snpNeedData_mshr_s3 && !hasValidDataBuf_s6s7 && valid_s3
    snpReplay_dup_s3 := snpReplay_s3

    /** Deal with acquire/get reqeuests */
    val noSpaceForNonDataResp_s3 = io.nonDataRespCnt >= (nrNonDataSourceDEntry - 1).U // No space for ReleaseAck to send out to SourceD
    val acquireReplay_s3         = !mshrAlloc_s3 && ((isAcquireBlock_s3 || isGet_s3) && !hasValidDataBuf_s6s7 || isAcquirePerm_s3 && noSpaceForNonDataResp_s3) && valid_s3
    val getReplay_s3             = isGet_s3 && !mshrAlloc_s3 && !hasValidDataBuf_s6s7 && valid_s3

    /** Deal with mshr cbwrdata */
    val isCopyBack_s3      = task_s3.isMshrTask && task_s3.isCHIOpcode && task_s3.opcode === CopyBackWrData
    val copyBackRetry_s3   = isCopyBack_s3 && !hasValidDataBuf_s6s7 && valid_s3
    val copyBackIsValid_s3 = isCopyBack_s3 && task_s3.resp =/= Resp.I

    /** Deal with mshr refill */
    val refillNeedData_mp_s3 = mpTask_refill_s3 && (task_s3.opcode === GrantData || task_s3.opcode === AccessAckData)
    val refillRetry_s3       = mpTask_refill_s3 && Mux(refillNeedData_mp_s3, !hasValidDataBuf_s6s7, noSpaceForNonDataResp_s3)

    /** Deal with mshr compdata */
    val isCompData_s3    = task_s3.isMshrTask && task_s3.isCHIOpcode && task_s3.opcode === CompData && supportDCT.B
    val compDataRetry_s3 = isCompData_s3 && !hasValidDataBuf_s6s7 && valid_s3 && supportDCT.B

    /** 
     * Get/Prefetch is not required to writeback [[Directory]].
     *     => Get is a TL-UL message which will not modify the coherent state
     *     => Prefetch only change the L2 state and not response upwards to L1
     */
    val snpNotUpdateDir_s3 = {
        val opcodes       = VecInit(SnpShared, SnpNotSharedDirty) ++ { if (supportDCT) Seq(SnpSharedFwd, SnpNotSharedDirtyFwd) else Nil }
        val opcodeMatchOH = VecInit(opcodes.map(_ === task_s3.opcode))

        // @formatter:off
        (
            !dirResp_s3.hit ||
            dirResp_s3.hit && (
                meta_s3.isBranch && (!meta_s3.isDirty && opcodeMatchOH.asUInt.orR || isSnpOnceX_s3) ||
                meta_s3.isTip && isSnpOnceX_s3
            ) ||
            task_s3.snpHitWriteBack ||
            task_s3.snpHitReq
        )
        // @formatter:on
    }
    val dirWen_mshr_s3 = task_s3.isMshrTask && task_s3.updateDir && !hasRetry_s3 // if stage2 has retry task, we should not update directory info.
    val dirWen_a_s3    = task_s3.isChannelA && !mshrAlloc_s3 && !isGet_s3 && !isPrefetch_s3 && !acquireReplay_s3
    val dirWen_b_s3    = task_s3.isChannelB && !mshrAlloc_s3 && isSnoop_s3 && !snpNotUpdateDir_s3 && !snpReplay_s3
    val dirWen_c_s3    = task_s3.isChannelC && hit_s3

    val newMeta_mshr_s3 = DirectoryMetaEntry(task_s3.tag, task_s3.newMetaEntry)

    val newMeta_a_s3 = DirectoryMetaEntry(
        state = Mux(reqNeedT_s3 || sinkRespPromoteT_a_s3, Mux(meta_s3.isDirty, MixedState.TTD, MixedState.TTC), meta_s3.state),
        tag = task_s3.tag,
        aliasOpt = Some(task_s3.aliasOpt.getOrElse(0.U)),
        clientsOH = reqClientOH_s3 | meta_s3.clientsOH
    )

    val snprespPassDirty_s3  = !isSnpOnceX_s3 && meta_s3.isDirty && hit_s3 || task_s3.snpHitWriteBack && task_s3.snpGotDirty
    val snprespFinalState_s3 = Mux(isSnpToN_s3, MixedState.I, Mux(task_s3.opcode === SnpCleanShared || isSnpOnceX_s3, meta_s3.state, MixedState.BC))
    val newMeta_b_s3 = DirectoryMetaEntry(
        state = snprespFinalState_s3,
        tag = meta_s3.tag,
        aliasOpt = Some(meta_s3.aliasOpt.getOrElse(0.U)),
        clientsOH = meta_s3.clientsOH
    )

    val newMeta_c_s3 = DirectoryMetaEntry(
        state = MuxCase(
            MixedState.I,
            Seq(
                (task_s3.param === TtoN && meta_s3.isDirty)                      -> MixedState.TD,
                (task_s3.param === TtoN && !meta_s3.isDirty && isReleaseData_s3) -> MixedState.TD,
                (task_s3.param === TtoN && !meta_s3.isDirty && isRelease_s3)     -> MixedState.TC,
                (task_s3.param === TtoB && meta_s3.isDirty)                      -> MixedState.TD,
                (task_s3.param === TtoB && !meta_s3.isDirty && isReleaseData_s3) -> MixedState.TD,
                (task_s3.param === TtoB && !meta_s3.isDirty && isRelease_s3)     -> MixedState.TC,
                (task_s3.param === BtoN && meta_s3.state === MixedState.TC)      -> MixedState.TC,
                (task_s3.param === BtoN && meta_s3.state === MixedState.TD)      -> MixedState.TD,
                (task_s3.param === BtoN && meta_s3.state === MixedState.BC)      -> MixedState.BC,
                (task_s3.param === BtoN && meta_s3.state === MixedState.BD)      -> MixedState.BD
            )
        ),
        tag = meta_s3.tag,
        aliasOpt = Some(meta_s3.aliasOpt.getOrElse(0.U)),
        clientsOH = Mux(task_s3.param === TtoN || task_s3.param === BtoN, meta_s3.clientsOH & ~reqClientOH_s3, meta_s3.clientsOH /* Release.TtoB */ )
    )

    // Release* cannot nest ProbeAck and the MSHR does not write back directory info,
    // so we should not update the directory info as the clientsOH of the MSHR task
    // may be wrong due to Release* is unable to nest the MSHR in a timely manner.
    val cancelDirWen_c_s3 = task_s3.isChannelC && task_s3.param === BtoN && (meta_s3.state === MixedState.TTC || meta_s3.state === MixedState.TTD)
    assert(
        !(dirWen_c_s3 && !cancelDirWen_c_s3 && newMeta_c_s3.state === MixedState.I),
        "[Release] Unexpected newMeta_c_s3 state => MixedState.I! opcode:%d param:%d set:%d/0x%x tag:%d/0x%x source:%d/0x%x meta_s3.state:%d",
        task_s3.opcode,
        task_s3.param,
        task_s3.set,
        task_s3.set,
        task_s3.tag,
        task_s3.tag,
        task_s3.source,
        task_s3.source,
        meta_s3.state
    )

    // Here we cache the delayed info of direcoty info written by the Release*.
    // When the MSHR task is written back to the directory, we should use the
    // delayed info to correct the clientsOH which is wrong due to Release* is
    // unable to nest the MSHR in a timely manner.
    val dirWen_c_s3_dly1      = RegNext(dirWen_c_s3, false.B)
    val dirWrSet_s3_dly1      = RegEnable(task_s3.set, dirWen_c_s3)
    val dirWrWayOH_s3_dly1    = RegEnable(io.dirResp_s3.bits.wayOH, dirWen_c_s3)
    val dirWrClientOH_s3_dly1 = RegEnable(newMeta_c_s3.clientsOH, dirWen_c_s3)

    val dirWen_c_s3_dly2      = RegNext(dirWen_c_s3_dly1, false.B)
    val dirWrSet_s3_dly2      = RegEnable(dirWrSet_s3_dly1, dirWen_c_s3_dly1)
    val dirWrWayOH_s3_dly2    = RegEnable(dirWrWayOH_s3_dly1, dirWen_c_s3_dly1)
    val dirWrClientOH_s3_dly2 = RegEnable(dirWrClientOH_s3_dly1, dirWen_c_s3_dly1)

    when(task_s3.isMshrTask && task_s3.updateDir) {
        when(dirWen_c_s3_dly1 && task_s3.set === dirWrSet_s3_dly1 && task_s3.wayOH === dirWrWayOH_s3_dly1) {
            newMeta_mshr_s3.clientsOH := task_s3.newMetaEntry.clientsOH & dirWrClientOH_s3_dly1
        }
        when(dirWen_c_s3_dly2 && task_s3.set === dirWrSet_s3_dly2 && task_s3.wayOH === dirWrWayOH_s3_dly2) {
            newMeta_mshr_s3.clientsOH := task_s3.newMetaEntry.clientsOH & dirWrClientOH_s3_dly2
        }
    }

    val dirWen_s3 = (!mshrAlloc_s3 && (dirWen_mshr_s3 || dirWen_a_s3 || dirWen_b_s3 || dirWen_c_s3 && !cancelDirWen_c_s3) || mshrAlloc_lp_s3) && valid_s3
    io.dirWrite_s3.valid      := dirWen_s3
    io.dirWrite_s3.bits.set   := task_s3.set
    io.dirWrite_s3.bits.wayOH := Mux(!task_s3.isMshrTask && hit_s3, dirResp_s3.wayOH, task_s3.wayOH)
    io.dirWrite_s3.bits.meta := MuxCase(
        0.U.asTypeOf(new DirectoryMetaEntry),
        Seq(
            dirWen_mshr_s3  -> newMeta_mshr_s3,
            dirWen_a_s3     -> newMeta_a_s3,
            dirWen_b_s3     -> newMeta_b_s3,
            dirWen_c_s3     -> newMeta_c_s3,
            mshrAlloc_lp_s3 -> DirectoryMetaEntry() // LowPower request will clear directory
        )
    )
    assert(
        !(io.dirWrite_s3.fire && io.dirWrite_s3.bits.meta.isTrunk && PopCount(io.dirWrite_s3.bits.meta.clientsOH) > 1.U),
        "Trunk should only have one client! addr: 0x%x",
        Cat(task_s3.tag, task_s3.set, 0.U(6.W))
    )

    val lowPowerNeedProbe_s3  = mshrAlloc_lp_s3 && dirResp_s3.meta.clientsOH.orR
    val replRespValid_s3      = io.replResp_s3.fire && !io.replResp_s3.bits.retry
    val replRespNeedProbe_s3  = replRespValid_s3 && io.replResp_s3.bits.meta.clientsOH.orR || lowPowerNeedProbe_s3
    val replRespTag_s3        = Mux(lowPowerNeedProbe_s3, dirResp_s3.meta.tag, io.replResp_s3.bits.meta.tag)
    val replRespWayOH_s3      = Mux(lowPowerNeedProbe_s3, task_s3.wayOH, io.replResp_s3.bits.wayOH)
    val allocMshrIdx_s3       = OHToUInt(io.mshrFreeOH_s3)                                          // TODO: consider OneHot?
    val needAllocDestSinkC_s3 = (needProbeOnHit_a_s3 || needProbe_b_s3 || needDCT_s3) && mshrAlloc_s3 || replRespNeedProbe_s3
    val sinkDataToTempDS_s3   = needProbe_b_s3 || (isGet_s3 || isAcquire_s3) && needProbeOnHit_a_s3 // AcquireBlock need GrantData response, nested ReleaseData will be saved into the TempDataStorage

    /**
     *  If L2 is TRUNK, L1 migh owns a dirty cacheline, any dirty data should be updated in L2. GrantData must contain clean cacheline data.
     *  If we receive a replResp_s3, we should always not write data into [[DataStorage]] as the received dirty data will be written back into next level cache due to replacement.
     */
    val sinkDataToDS_s3 = /* meta_s3.isTrunk || */ replRespNeedProbe_s3 // Dirty data caused by replacement probe operation should be written into DataStorage
    assert(!(io.replResp_s3.fire && !task_s3.isMshrTask), "replResp_s3 should only be valid when task_s3.isMshrTask")

    /** read data from [[DataStorage]] */
    val readToTempDS_s3   = io.mshrAlloc_s3.fire && (needProbeOnHit_a_s3 || needReadOnHit_a_s3 || needProbe_b_s3 || needDCT_s3) // Read data into TempDataStorage
    val readOnHit_s3      = hit_s3 && (isAcquireBlock_s3 && !acquireReplay_s3 || isGet_s3 && !getReplay_s3) && !mshrAlloc_s3
    val readOnCopyBack_s3 = isCopyBack_s3 && task_s3.channel === CHIChannel.TXDAT && !copyBackRetry_s3 && copyBackIsValid_s3
    val readOnCompData_s3 = isCompData_s3 && !task_s3.readTempDs && task_s3.channel === CHIChannel.TXDAT && !compDataRetry_s3 && supportDCT.B
    val readOnSnpOK_s3    = snpNeedData_b_s3 && !mshrAlloc_s3 && !snpReplay_s3
    val readOnMpTask_s3   = !task_s3.readTempDs && (mpTask_snpresp_s3 && task_s3.channel === CHIChannel.TXDAT && !snpRetry_s3 || mpTask_refill_s3 && refillNeedData_mp_s3 && !refillRetry_s3)
    io.toDS.dsWrWayOH_s3.valid   := valid_s3 && !task_s3.isMshrTask && task_s3.opcode === ReleaseData && task_s3.isChannelC
    io.toDS.dsWrWayOH_s3.bits    := dirResp_s3.wayOH // provide WayOH for SinkC(ReleaseData) to write DataStorage
    io.toDS.mshrId_s3            := Mux(readOnCopyBack_s3 || readOnCompData_s3, task_s3.mshrId, allocMshrIdx_s3)
    io.toDS.dsRead_s3.valid      := valid_s3 && (readOnHit_s3 || readToTempDS_s3 || readOnCopyBack_s3 || readOnCompData_s3 || readOnSnpOK_s3 || readOnMpTask_s3)
    io.toDS.dsRead_s3.bits.set   := task_s3.set
    io.toDS.dsRead_s3.bits.wayOH := Mux(readOnCopyBack_s3 || readOnCompData_s3 || readOnMpTask_s3, task_s3.wayOH, io.dirResp_s3.bits.wayOH)
    io.toDS.dsRead_s3.bits.dest := MuxCase(
        DataDestination.SourceD,
        Seq(
            (readOnCopyBack_s3 || readOnCompData_s3 || readOnMpTask_s3 && mpTask_snpresp_s3) -> DataDestination.TXDAT,
            (needProbeOnHit_a_s3 || (readOnSnpOK_s3 || readToTempDS_s3) && mshrAlloc_s3)     -> DataDestination.TempDataStorage,
            readOnSnpOK_s3                                                                   -> DataDestination.TXDAT,
            mpTask_refill_s3                                                                 -> DataDestination.SourceD
        )
    )
    when(valid_s3) {
        assert(
            PopCount(Cat(readOnHit_s3, readToTempDS_s3, readOnCopyBack_s3, readOnSnpOK_s3)) <= 1.U,
            "multiple ds read! %b",
            Cat(readOnHit_s3, readToTempDS_s3, readOnCopyBack_s3, readOnSnpOK_s3)
        )
    }

    val valid_cbwrdata_mp_s3 = isCopyBack_s3 && task_s3.channel === CHIChannel.TXDAT && valid_s3
    val valid_compdata_mp_s3 = isFwdSnoop_s3 && task_s3.channel === CHIChannel.TXDAT && valid_s3 && supportDCT.B
    val valid_snpdata_s3     = snpNeedData_b_s3 && !task_s3.isMshrTask && !mshrAlloc_s3 && !snpReplay_s3 && valid_s3
    val valid_snpdata_mp_s3  = mpTask_snpresp_s3 && !task_s3.readTempDs && task_s3.channel === CHIChannel.TXDAT && valid_s3
    val valid_snpresp_s3     = !snpNeedData_b_s3 && snpChnlReqOK_s3 && !snpReplay_s3
    val valid_snpresp_mp_s3  = mpTask_snpresp_s3 && task_s3.channel === CHIChannel.TXRSP
    val valid_reqbuf_s3      = valid_s3 && task_s3.isChannelA && !optParam.sinkaStallOnReqArb.B
    val valid_snpbuf_s3      = valid_s3 && task_s3.isChannelB
    val valid_replay_s3      = mshrReplay_s3 || snpReplay_s3 || acquireReplay_s3 || getReplay_s3
    val valid_refill_mp_s3   = mpTask_refill_s3
    val valid_refill_s3      = !mshrAlloc_s3 && task_s3.isChannelA && !acquireReplay_s3 && !getReplay_s3 && valid_s3

    val respParam_s3  = Mux(task_s3.isChannelA, Mux(task_s3.param === NtoB && !sinkRespPromoteT_a_s3, toB, toT), DontCare)
    val respOpcode_s3 = WireInit(0.U(math.max(task_s3.opcode.getWidth, task_s3.chiOpcode.getWidth).W))
    respOpcode_s3 := MuxCase(
        DontCare,
        Seq(
            isAcquireBlock_s3                  -> GrantData,     // to SourceD
            isAcquirePerm_s3                   -> Grant,         // to SourceD
            isGet_s3                           -> AccessAckData, // to SourceD
            (isRelease_s3 || isReleaseData_s3) -> ReleaseAck,    // to SourceD
            (isSnoop_s3 && !snpNeedData_b_s3)  -> SnpResp,       // toTXRSP
            (isSnoop_s3 && snpNeedData_b_s3)   -> SnpRespData    // toTXDAT
        )
    )

    /**
      * The Snoop response cache state information provides the state of the cache line after the Snoop response is sent.
      */
    val snpResp_s3 =
        Mux(
            task_s3.isMshrTask,
            task_s3.resp,
            Mux(
                task_s3.snpHitReq,
                Mux(isSnpToN_s3, Resp.I, Resp.SC),
                Resp.setPassDirty(
                    Mux(
                        !dirResp_s3.hit,
                        Resp.I,
                        MuxCase(
                            Resp.I,
                            Seq(
                                (snprespFinalState_s3 === MixedState.BC)  -> Resp.SC,
                                (snprespFinalState_s3 === MixedState.BD)  -> Resp.SD, // TODO:
                                (snprespFinalState_s3 === MixedState.TC)  -> Resp.UC,
                                (snprespFinalState_s3 === MixedState.TD)  -> Resp.UD, // TODO:
                                (snprespFinalState_s3 === MixedState.TTC) -> Resp.UC,
                                (snprespFinalState_s3 === MixedState.TTD) -> Resp.UD  // TODO:
                            )
                        )
                    ),
                    snprespPassDirty_s3 && !CHIOpcodeSNP.isSnpMakeInvalidX(task_s3.opcode)
                )
            )
        )

    val fire_s3 = valid_reqbuf_s3 || valid_snpbuf_s3 || needAllocDestSinkC_s3 || valid_replay_s3 || valid_refill_s3 || valid_cbwrdata_mp_s3 || valid_compdata_mp_s3 || valid_snpresp_s3 || valid_snpresp_mp_s3 || valid_snpdata_s3 || valid_snpdata_mp_s3 || valid_snpresp_mp_s3 || valid_refill_mp_s3
    assert(!(valid_replay_s3 && valid_refill_s3), "Only one of valid_replay_s3 and valid_refill_s3 can be true!")

    // -----------------------------------------------------------------------------------------
    // Stage 4
    // -----------------------------------------------------------------------------------------
    val task_s4              = RegInit(0.U.asTypeOf(new TaskBundle))
    val replRespNeedProbe_s4 = RegEnable(replRespNeedProbe_s3, fire_s3)
    val replRespTag_s4       = RegEnable(replRespTag_s3, fire_s3)
    val replRespWayOH_s4     = RegEnable(replRespWayOH_s3, fire_s3)
    val allocMshrIdx_s4      = RegEnable(allocMshrIdx_s3, fire_s3)
    val sinkDataToTempDS_s4  = RegEnable(sinkDataToTempDS_s3, fire_s3)
    val sinkDataToDS_s4      = RegEnable(sinkDataToDS_s3, fire_s3)
    val dirRespWayOH_s4      = RegEnable(io.dirResp_s3.bits.wayOH, fire_s3)
    val respOpcode_s4        = RegEnable(respOpcode_s3, fire_s3)
    val respParam_s4         = RegEnable(respParam_s3, fire_s3)
    val snpResp_s4           = RegEnable(snpResp_s3, fire_s3)
    val snpRetry_s4          = RegEnable(snpRetry_s3, fire_s3)
    val refillRetry_s4       = RegEnable(refillRetry_s3, fire_s3)
    val copyBackRetry_s4     = RegEnable(copyBackRetry_s3, fire_s3)

    val valid_s4              = RegNext(fire_s3, false.B)
    val needAllocDestSinkC_s4 = valid_s4 && RegEnable(needAllocDestSinkC_s3 && !valid_replay_s3, false.B, fire_s3)
    val valid_cbwrdata_mp_s4  = valid_s4 && RegEnable(valid_cbwrdata_mp_s3, false.B, fire_s3)
    val valid_compdata_mp_s4  = valid_s4 && RegEnable(valid_compdata_mp_s3, false.B, fire_s3) && supportDCT.B
    val valid_snpdata_s4      = valid_s4 && RegEnable(valid_snpdata_s3, false.B, fire_s3)
    val valid_snpdata_mp_s4   = valid_s4 && RegEnable(valid_snpdata_mp_s3, false.B, fire_s3)
    val valid_snpresp_s4      = valid_s4 && RegEnable(valid_snpresp_s3, false.B, fire_s3)
    val valid_snpresp_mp_s4   = valid_s4 && RegEnable(valid_snpresp_mp_s3, false.B, fire_s3)
    val valid_reqbuf_s4       = valid_s4 && RegEnable(valid_reqbuf_s3, false.B, fire_s3) && !optParam.sinkaStallOnReqArb.B
    val valid_snpbuf_s4       = valid_s4 && RegEnable(valid_snpbuf_s3, false.B, fire_s3)
    val valid_replay_s4       = valid_s4 && RegEnable(valid_replay_s3, false.B, fire_s3)
    val valid_refill_mp_s4    = valid_s4 && RegEnable(valid_refill_mp_s3, false.B, fire_s3)
    val valid_refill_s4       = valid_s4 && RegEnable(valid_refill_s3, false.B, fire_s3)

    val fire_s4         = valid_cbwrdata_mp_s4 || valid_compdata_mp_s4 || valid_refill_s4 || valid_snpdata_s4 || valid_snpdata_mp_s4 || valid_refill_mp_s4
    val needsDataBuf_s4 = valid_cbwrdata_mp_s4 || valid_compdata_mp_s4 || valid_snpdata_s4 || valid_snpdata_mp_s4 || ((valid_refill_s4 || valid_refill_mp_s4) && (task_s4.opcode === AccessAckData || task_s4.opcode === GrantData)) // TODO: refill mp not retry

    val validVec_s4 = Cat(
        needAllocDestSinkC_s4,
        valid_cbwrdata_mp_s4,
        valid_compdata_mp_s4,
        valid_snpdata_s4,
        valid_snpdata_mp_s4,
        valid_snpresp_s4,
        valid_snpresp_mp_s4,
        valid_replay_s4,
        valid_refill_s4,
        valid_refill_mp_s4
    )
    assert(PopCount(validVec_s4) <= 1.U, "validVec_s4:0b%b", validVec_s4)

    when(fire_s3) {
        task_s4 := task_s3
    }

    io.replayOpt_s4.foreach { replay_s4 =>
        replay_s4.valid     := valid_replay_s4
        replay_s4.bits.task := task_s4
    }

    io.snpBufReplay_s4.valid             := valid_snpbuf_s4
    io.snpBufReplay_s4.bits.shouldReplay := valid_replay_s4
    io.snpBufReplay_s4.bits.txnID        := task_s4.txnID
    io.snpBufReplay_s4.bits.set          := task_s4.set
    io.snpBufReplay_s4.bits.tag          := task_s4.tag

    io.reqBufReplay_s4_opt.foreach { reqBufReplay_s4 =>
        reqBufReplay_s4.valid             := valid_reqbuf_s4
        reqBufReplay_s4.bits.shouldReplay := valid_replay_s4
        reqBufReplay_s4.bits.source       := task_s4.source
        reqBufReplay_s4.bits.isPrefetch   := task_s4.opcode === Hint
        reqBufReplay_s4.bits.set          := task_s4.set
        reqBufReplay_s4.bits.tag          := task_s4.tag
    }

    io.allocDestSinkC_s4.valid         := needAllocDestSinkC_s4
    io.allocDestSinkC_s4.bits.mshrId   := Mux(replRespNeedProbe_s4, task_s4.mshrId, allocMshrIdx_s4)
    io.allocDestSinkC_s4.bits.wayOH    := Mux(replRespNeedProbe_s4, replRespWayOH_s4, dirRespWayOH_s4)
    io.allocDestSinkC_s4.bits.set      := task_s4.set
    io.allocDestSinkC_s4.bits.tag      := Mux(replRespNeedProbe_s4, replRespTag_s4, task_s4.tag)
    io.allocDestSinkC_s4.bits.isTempDS := sinkDataToTempDS_s4
    io.allocDestSinkC_s4.bits.isDS     := sinkDataToDS_s4
    assert(
        !(io.allocDestSinkC_s4.valid && io.allocDestSinkC_s4.bits.isDS && !io.allocDestSinkC_s4.bits.wayOH.orR),
        "invalid wayOH! wayOH:0b%b",
        io.allocDestSinkC_s4.bits.wayOH
    )

    io.txrsp_s4.valid        := valid_snpresp_s4 || valid_snpresp_mp_s4
    io.txrsp_s4.bits.tgtID   := Mux(valid_snpresp_s4, task_s4.srcID, task_s4.tgtID)
    io.txrsp_s4.bits.txnID   := task_s4.txnID
    io.txrsp_s4.bits.dbID    := task_s4.txnID
    io.txrsp_s4.bits.opcode  := SnpResp
    io.txrsp_s4.bits.resp    := snpResp_s4
    io.txrsp_s4.bits.respErr := RespErr.NormalOkay

    val txrspStall_s4 = io.txrsp_s4.valid && !io.txrsp_s4.ready
    io.retryTasks.mshrId_s4                := task_s4.mshrId
    io.retryTasks.stage4.valid             := valid_snpresp_mp_s4 || valid_snpdata_mp_s4 || valid_cbwrdata_mp_s4 || valid_refill_mp_s4
    io.retryTasks.stage4.bits.isRetry_s4   := txrspStall_s4 || snpRetry_s4 || copyBackRetry_s4 || refillRetry_s4
    io.retryTasks.stage4.bits.grant_s4     := valid_refill_mp_s4 && (task_s4.opcode === GrantData || task_s4.opcode === Grant)
    io.retryTasks.stage4.bits.accessack_s4 := valid_refill_mp_s4 && (task_s4.opcode === AccessAckData)
    io.retryTasks.stage4.bits.cbwrdata_s4  := valid_cbwrdata_mp_s4
    io.retryTasks.stage4.bits.snpresp_s4   := valid_snpresp_mp_s4 || valid_snpdata_mp_s4

    val refillNeedData_s4 = valid_refill_s4 && !task_s4.isCHIOpcode && needData(respOpcode_s4) || valid_refill_mp_s4 && RegEnable(refillNeedData_mp_s3, fire_s3)
    io.sourceD_s4.valid       := (valid_refill_s4 || valid_refill_mp_s4) && !refillNeedData_s4
    io.sourceD_s4.bits        := task_s4
    io.sourceD_s4.bits.opcode := Mux(valid_refill_mp_s4, task_s4.opcode, respOpcode_s4)
    io.sourceD_s4.bits.param  := Mux(valid_refill_mp_s4, task_s4.param, respParam_s4)
    assert(!(io.sourceD_s4.valid && !io.sourceD_s4.ready), "sourceD_s4(NonDataResp) should not be stalled!")

    // -----------------------------------------------------------------------------------------
    // Stage 5
    // -----------------------------------------------------------------------------------------
    val task_s5              = RegInit(0.U.asTypeOf(new TaskBundle))
    val valid_s5             = RegNext(fire_s4, false.B)
    val valid_snpdata_s5     = valid_s5 && RegEnable(valid_snpdata_s4, false.B, fire_s4)
    val valid_snpdata_mp_s5  = valid_s5 && RegEnable(valid_snpdata_mp_s4 && !snpRetry_s4, false.B, fire_s4)
    val valid_cbwrdata_mp_s5 = valid_s5 && RegEnable(valid_cbwrdata_mp_s4 && !copyBackRetry_s4, false.B, fire_s4)
    val valid_refill_s5      = valid_s5 && RegEnable(valid_refill_s4 && refillNeedData_s4, false.B, fire_s4)
    val valid_refill_mp_s5   = valid_s5 && RegEnable(valid_refill_mp_s4 && !refillRetry_s4 && refillNeedData_s4, false.B, fire_s4)
    val fire_s5              = valid_refill_s5 || valid_cbwrdata_mp_s5 || valid_snpdata_s5 || valid_snpdata_mp_s5 || valid_refill_mp_s5
    val needsDataBuf_s5      = valid_snpdata_s5 || valid_snpdata_mp_s5 || valid_cbwrdata_mp_s5 || ((valid_refill_s5 || valid_refill_mp_s5) && (task_s5.opcode === AccessAckData || task_s5.opcode === GrantData))

    when(fire_s4) {
        task_s5 := task_s4

        when(valid_snpdata_s4) {
            task_s5.tgtID := task_s4.srcID
        }

        when(!task_s4.isMshrTask) {
            task_s5.wayOH  := dirRespWayOH_s4
            task_s5.opcode := respOpcode_s4
            task_s5.param  := Mux(!task_s4.isCHIOpcode, respParam_s4, snpResp_s4)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Stage 6
    // -----------------------------------------------------------------------------------------
    val valid_s7_dup     = WireInit(false.B)
    val isSourceD_s7_dup = WireInit(false.B)
    val isTXDAT_s7_dup   = WireInit(false.B)

    val task_s6      = RegEnable(task_s5, 0.U.asTypeOf(new TaskBundle), fire_s5)
    val valid_s6     = RegInit(false.B)
    val isSourceD_s6 = !task_s6.isCHIOpcode
    val isTXDAT_s6   = task_s6.isCHIOpcode
    val fire_s6      = valid_s6 && ready_s7 && (io.sourceD_s6s7.valid && !io.sourceD_s6s7.ready || io.txdat_s6s7.valid && !io.txdat_s6s7.ready)

    when(fire_s5) {
        valid_s6 := true.B
        assert(!(valid_s7_dup && isSourceD_s7_dup && valid_s6 && isSourceD_s6), "stage6 is full!")
        assert(!(valid_s7_dup && isTXDAT_s7_dup && valid_s6 && isTXDAT_s6), "stage6 is full!")
    }.elsewhen((io.sourceD_s6s7.fire && isSourceD_s6 && !(valid_s7_dup && isSourceD_s7_dup) || io.txdat_s6s7.fire && isTXDAT_s6 && !(valid_s7_dup && isTXDAT_s7_dup)) && !fire_s5 && valid_s6) {
        valid_s6 := false.B
    }.elsewhen(fire_s6 && !fire_s5) {
        valid_s6 := false.B
    }

    // -----------------------------------------------------------------------------------------
    // Stage 7
    // -----------------------------------------------------------------------------------------
    val task_s7      = RegEnable(task_s6, 0.U.asTypeOf(new TaskBundle), fire_s6)
    val valid_s7     = RegInit(false.B)
    val isSourceD_s7 = !task_s7.isCHIOpcode
    val isTXDAT_s7   = task_s7.isCHIOpcode
    val fire_s7      = io.sourceD_s6s7.fire && isSourceD_s7 || io.txdat_s6s7.fire && isTXDAT_s7
    valid_s7_dup     := valid_s7
    isSourceD_s7_dup := isSourceD_s7
    isTXDAT_s7_dup   := isTXDAT_s7
    ready_s7         := !valid_s7

    when(fire_s6) {
        valid_s7 := true.B
    }.elsewhen(fire_s7 && valid_s7 && !fire_s6) {
        valid_s7 := false.B
    }

    io.sourceD_s6s7.valid := valid_s6 && isSourceD_s6 || valid_s7 && isSourceD_s7
    io.sourceD_s6s7.bits  := Mux(valid_s7 && isSourceD_s7, task_s7, task_s6)

    io.txdat_s6s7.valid       := valid_s6 && isTXDAT_s6 || valid_s7 && isTXDAT_s7
    io.txdat_s6s7.bits        := DontCare
    io.txdat_s6s7.bits.tgtID  := Mux(valid_s7 && isTXDAT_s7, task_s7.tgtID, task_s6.tgtID)
    io.txdat_s6s7.bits.txnID  := Mux(valid_s7 && isTXDAT_s7, task_s7.txnID, task_s6.txnID)
    io.txdat_s6s7.bits.dbID   := Mux(valid_s7 && isTXDAT_s7, task_s7.txnID, task_s6.txnID)
    io.txdat_s6s7.bits.be     := Fill(beatBytes, 1.U)
    io.txdat_s6s7.bits.opcode := Mux(valid_s7 && isTXDAT_s7, task_s7.opcode, task_s6.opcode)
    io.txdat_s6s7.bits.resp   := Mux(valid_s7 && isTXDAT_s7, task_s7.resp, task_s6.resp) // For WriteData responses, this field indicates the state of the data in the Request Node when the data is sent.

    val mayUseDataBufCnt = PopCount(Cat(needsDataBuf_s4, needsDataBuf_s5, valid_s6, valid_s7))
    hasValidDataBuf_s6s7 := mayUseDataBufCnt < 2.U

    /**
     * Output status info for [[SourceB]] or other modules.
     * The [[SourceB]] may use this info to decide whether to block an Probe from L2Cache to upstream cache.(Probe request should not be issued until pending Grant has received GrantAck according to TileLink spec.) 
     */
    io.status.stage4.valid    := valid_s4
    io.status.stage4.set      := task_s4.set
    io.status.stage4.tag      := task_s4.tag
    io.status.stage4.isRefill := !task_s4.isCHIOpcode && (task_s4.opcode === Grant || task_s4.opcode === GrantData || task_s4.opcode === AccessAckData) && valid_refill_s4

    io.status.stage5.valid    := valid_s5
    io.status.stage5.set      := task_s5.set
    io.status.stage5.tag      := task_s5.tag
    io.status.stage5.isRefill := !task_s5.isCHIOpcode && (task_s5.opcode === Grant || task_s5.opcode === GrantData || task_s5.opcode === AccessAckData)

    io.status.stage6.valid    := valid_s6
    io.status.stage6.set      := task_s6.set
    io.status.stage6.tag      := task_s6.tag
    io.status.stage6.isRefill := isSourceD_s6 && (task_s6.opcode === Grant || task_s6.opcode === GrantData || task_s6.opcode === AccessAckData)

    io.status.stage7.valid    := valid_s7
    io.status.stage7.set      := task_s7.set
    io.status.stage7.tag      := task_s7.tag
    io.status.stage7.isRefill := isSourceD_s7 && (task_s7.opcode === Grant || task_s7.opcode === GrantData || task_s7.opcode === AccessAckData)

    /**
     * Debug signals.
     * Can be removed if not necessary. 
     */
    val addr_debug_s2 = Cat(task_s2.tag, task_s2.set, 0.U(6.W))
    val addr_debug_s3 = Cat(task_s3.tag, task_s3.set, 0.U(6.W))
    val addr_debug_s4 = Cat(task_s4.tag, task_s4.set, 0.U(6.W))
    val addr_debug_s5 = Cat(task_s5.tag, task_s5.set, 0.U(6.W))
    val addr_debug_s6 = Cat(task_s6.tag, task_s6.set, 0.U(6.W))
    val addr_debug_s7 = Cat(task_s7.tag, task_s7.set, 0.U(6.W))
    MultiDontTouch(addr_debug_s2, addr_debug_s3, addr_debug_s4, addr_debug_s5, addr_debug_s6, addr_debug_s7)

    io.prefetchTrainOpt.foreach { train =>
        train.valid           := valid_s3 && ((isAcquire_s3 || isGet_s3) && task_s3.needHintOpt.getOrElse(false.B) && (!dirResp_s3.hit || meta_s3.fromPrefetchOpt.getOrElse(false.B)))
        train.bits.hit        := dirResp_s3.hit
        train.bits.tag        := task_s3.tag
        train.bits.set        := task_s3.set
        train.bits.needT      := reqNeedT_s3 // TODO: mergeA
        train.bits.source     := task_s3.source
        train.bits.prefetched := meta_s3.fromPrefetchOpt.getOrElse(false.B)
        train.bits.pfsource   := DontCare    // TODO: only for TopDown?
        train.bits.reqsource  := DontCare    // TODO: only for TopDown?
        train.bits.vaddr.foreach(_ := task_s3.vaddrOpt.getOrElse(0.U))
    }

    // -----------------------------------------------------------------------------------------
    // Performance counters
    // -----------------------------------------------------------------------------------------
    println(s"[${this.getClass().toString()}] perfEnable:${p(DebugOptionsKey).EnablePerfDebug && !p(DebugOptionsKey).FPGAPlatform}")

    def isChannelTask_s3(chnl: String, isReplay: Boolean = false) = {
        val isChannel = if (chnl == "A") {
            task_s3.isChannelA
        } else if (chnl == "B") {
            task_s3.isChannelB
        } else if (chnl == "C") {
            task_s3.isChannelC
        } else {
            assert(false, "Unknown channel")
            false.B
        }

        if (isReplay) {
            isChannel && !task_s3.isMshrTask && task_s3.isReplayTask && valid_s3
        } else {
            isChannel && !task_s3.isMshrTask && !task_s3.isReplayTask && valid_s3
        }
    }

    XSPerfAccumulate("sinkA_req_cnt", isChannelTask_s3("A"))
    XSPerfAccumulate("snoop_req_cnt", isChannelTask_s3("B"))
    XSPerfAccumulate("sinkC_req_cnt", isChannelTask_s3("C"))
    XSPerfAccumulate("sinkA_replay_req_cnt", isChannelTask_s3("A", true))
    XSPerfAccumulate("snoop_replay_req_cnt", isChannelTask_s3("B", true))

    XSPerfAccumulate("sinkA_hit_cnt", isChannelTask_s3("A") && dirResp_s3.hit)
    XSPerfAccumulate("sinkA_miss_cnt", isChannelTask_s3("A") && !dirResp_s3.hit)
    XSPerfAccumulate("snoop_hit_cnt", isChannelTask_s3("B") && dirResp_s3.hit)
    XSPerfAccumulate("snoop_miss_cnt", isChannelTask_s3("B") && !dirResp_s3.hit)

    XSPerfAccumulate("acquire_hit_cnt", isChannelTask_s3("A") && dirResp_s3.hit && (task_s3.opcode === AcquireBlock || task_s3.opcode === AcquirePerm))
    XSPerfAccumulate("acquire_miss_cnt", isChannelTask_s3("A") && !dirResp_s3.hit && (task_s3.opcode === AcquireBlock || task_s3.opcode === AcquirePerm))
    XSPerfAccumulate("get_hit_cnt", isChannelTask_s3("A") && dirResp_s3.hit && task_s3.opcode === Get)
    XSPerfAccumulate("get_miss_cnt", isChannelTask_s3("A") && !dirResp_s3.hit && task_s3.opcode === Get)

    XSPerfHistogram("sinkA_req_access_way", perfCnt = OHToUInt(dirResp_s3.wayOH), enable = isChannelTask_s3("A"), start = 0, stop = ways, step = 1)
    XSPerfHistogram("sinkA_req_hit_way", perfCnt = OHToUInt(dirResp_s3.wayOH), enable = isChannelTask_s3("A") && dirResp_s3.hit, start = 0, stop = ways, step = 1)

    XSPerfAccumulate("snoop_hit_req_cnt", isChannelTask_s3("B") && task_s3.snpHitReq)
    XSPerfAccumulate("snoop_hit_wb_cnt", isChannelTask_s3("B") && task_s3.snpHitWriteBack)

    XSPerfAccumulate("mshr_full_cnt", io.mshrAlloc_s3.valid && !io.mshrAlloc_s3.ready)
    XSPerfAccumulate("txdat_s2_stall_cnt", io.txdat_s2.valid && !io.txdat_s2.ready)
    XSPerfAccumulate("txrsp_s4_stall_cnt", io.txrsp_s4.valid && !io.txrsp_s4.ready)

    XSPerfAccumulate("mshr_snpRetry_s4_cnt", io.retryTasks.stage4.valid && io.retryTasks.stage4.bits.isRetry_s4 && snpRetry_s4)
    XSPerfAccumulate("mshr_copyBackRetry_s4_cnt", io.retryTasks.stage4.valid && io.retryTasks.stage4.bits.isRetry_s4 && copyBackRetry_s4)
    XSPerfAccumulate("mshr_refillRetry_s4_cnt", io.retryTasks.stage4.valid && io.retryTasks.stage4.bits.isRetry_s4 && refillRetry_s4)
    if (!optParam.sinkaStallOnReqArb) {
        XSPerfAccumulate("reqBufReplay_s4_cnt", io.reqBufReplay_s4_opt.get.valid)
    } else {
        XSPerfAccumulate("replay_s4_cnt", io.replayOpt_s4.get.valid)
    }

    dontTouch(io)
}

object MainPipe extends App {
    val config = SimpleL2.DefaultConfig()

    GenerateVerilog(args, () => new MainPipe()(config), name = "MainPipe", split = false)
}
