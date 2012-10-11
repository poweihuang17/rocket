package rocket

import Chisel._
import Node._;

import Constants._
import Instructions._
import hwacha._

class ioDpathAll extends Bundle()
{
  val host  = new ioHTIF();
  val ctrl  = new ioCtrlDpath().flip
  val dmem = new ioHellaCache
  val dtlb = new ioDTLB_CPU_req_bundle().asOutput()
  val imem  = new IOCPUFrontend
  val ptbr_wen = Bool(OUTPUT);
  val ptbr = UFix(OUTPUT, PADDR_BITS);
  val fpu = new ioDpathFPU();
  val vec_ctrl = new ioCtrlDpathVec().flip
  val vec_iface = new ioDpathVecInterface()
  val vec_imul_req = new io_imul_req
  val vec_imul_resp = Bits(INPUT, hwacha.Constants.SZ_XLEN)
}

class rocketDpath extends Component
{
  val io  = new ioDpathAll();

  val pcr = new rocketDpathPCR(); 
  val ex_pcr = pcr.io.r.data;

  val alu = new ALU
  val ex_alu_out = alu.io.out; 
  val ex_alu_adder_out = alu.io.adder_out; 
  
  val rfile = new rocketDpathRegfile();

  // execute definitions
  val ex_reg_pc             = Reg() { UFix() };
  val ex_reg_inst           = Reg() { Bits() };
  val ex_reg_raddr1         = Reg() { UFix() };
  val ex_reg_raddr2         = Reg() { UFix() };
  val ex_reg_op2            = Reg() { Bits() };
  val ex_reg_rs2            = Reg() { Bits() };
  val ex_reg_rs1            = Reg() { Bits() };
  val ex_reg_waddr          = Reg() { UFix() };
  val ex_reg_ctrl_fn_dw     = Reg() { UFix() };
  val ex_reg_ctrl_fn_alu    = Reg() { UFix() };
  val ex_reg_ctrl_sel_wb    = Reg() { UFix() };
 	val ex_wdata						  = Bits(); 	

  // memory definitions
  val mem_reg_pc             = Reg() { UFix() };
  val mem_reg_inst           = Reg() { Bits() };
  val mem_reg_rs2            = Reg() { Bits() };
  val mem_reg_waddr          = Reg() { UFix() };
  val mem_reg_wdata          = Reg() { Bits() };
  val mem_reg_raddr1         = Reg() { UFix() };
  val mem_reg_raddr2         = Reg() { UFix() };
  
  // writeback definitions
  val wb_reg_pc             = Reg() { UFix() };
  val wb_reg_inst           = Reg() { Bits() };
  val wb_reg_rs2            = Reg() { Bits() };
  val wb_reg_waddr          = Reg() { UFix() }
  val wb_reg_wdata          = Reg() { Bits() }
  val wb_reg_dmem_wdata     = Reg() { Bits() }
  val wb_reg_vec_waddr      = Reg() { UFix() }
  val wb_reg_vec_wdata      = Reg() { Bits() }
  val wb_reg_raddr1         = Reg() { UFix() };
  val wb_reg_raddr2         = Reg() { UFix() };
  val wb_reg_ll_wb          = Reg(resetVal = Bool(false));
  val wb_wdata              = Bits(); 	

  val dmem_resp_replay      = Bool()
  val r_dmem_resp_replay    = Reg(resetVal = Bool(false));
  val r_dmem_fp_replay      = Reg(resetVal = Bool(false));
  val r_dmem_resp_waddr     = Reg() { UFix() };

  val ex_pc_plus4 = ex_reg_pc + UFix(4);
  val ex_branch_target = ex_reg_pc + Cat(ex_reg_op2(VADDR_BITS-1,0), Bits(0,1)).toUFix

  val ex_ea_sign = Mux(ex_alu_adder_out(VADDR_BITS-1), ~ex_alu_adder_out(63,VADDR_BITS) === UFix(0), ex_alu_adder_out(63,VADDR_BITS) != UFix(0))
  val ex_effective_address = Cat(ex_ea_sign, ex_alu_adder_out(VADDR_BITS-1,0)).toUFix

  // hook up I$
  io.imem.req.bits.invalidateTLB := pcr.io.ptbr_wen
  io.imem.req.bits.currentpc := ex_reg_pc
  io.imem.req.bits.status := pcr.io.status
  io.imem.req.bits.pc :=
    Mux(io.ctrl.sel_pc === PC_EX4, ex_pc_plus4,
    Mux(io.ctrl.sel_pc === PC_EX,  Mux(io.ctrl.ex_jalr, ex_effective_address, ex_branch_target),
    Mux(io.ctrl.sel_pc === PC_PCR, Cat(pcr.io.evec(VADDR_BITS-1), pcr.io.evec).toUFix,
        wb_reg_pc))) // PC_WB

  // instruction decode stage
  val id_inst = io.imem.resp.bits.data
  val id_pc = io.imem.resp.bits.pc
  debug(id_inst)
  debug(id_pc)

  val id_raddr1 = id_inst(26,22).toUFix;
  val id_raddr2 = id_inst(21,17).toUFix;

	// regfile read
  rfile.io.r0.en   <> io.ctrl.ren2;
  rfile.io.r0.addr := id_raddr2;
  val id_rdata2 = rfile.io.r0.data;

  rfile.io.r1.en   <> io.ctrl.ren1;
  rfile.io.r1.addr := id_raddr1;
  val id_rdata1 = rfile.io.r1.data;

  // destination register selection
  val id_waddr =
    Mux(io.ctrl.sel_wa === WA_RD, id_inst(31,27).toUFix,
        RA); // WA_RA

  // bypass muxes
  val id_rs1_dmem_bypass =
    Mux(io.ctrl.ex_wen && id_raddr1 === ex_reg_waddr, Bool(false),
    Mux(io.ctrl.mem_wen && id_raddr1 === mem_reg_waddr, io.ctrl.mem_load,
        Bool(false)))
  val id_rs1 =
    Mux(io.ctrl.ex_wen && id_raddr1 === ex_reg_waddr,  ex_wdata,
    Mux(io.ctrl.mem_wen && id_raddr1 === mem_reg_waddr, mem_reg_wdata,
    Mux((io.ctrl.wb_wen || wb_reg_ll_wb) && id_raddr1 === wb_reg_waddr, wb_wdata,
        id_rdata1)))

  val id_rs2_dmem_bypass =
    Mux(io.ctrl.ex_wen && id_raddr2 === ex_reg_waddr, Bool(false),
    Mux(io.ctrl.mem_wen && id_raddr2 === mem_reg_waddr, io.ctrl.mem_load,
        Bool(false)))
  val id_rs2 =
    Mux(io.ctrl.ex_wen && id_raddr2 === ex_reg_waddr,  ex_wdata,
    Mux(io.ctrl.mem_wen && id_raddr2 === mem_reg_waddr, mem_reg_wdata,
    Mux((io.ctrl.wb_wen || wb_reg_ll_wb) && id_raddr2 === wb_reg_waddr, wb_wdata,
        id_rdata2)))

  // immediate generation
  val id_imm_bj = io.ctrl.sel_alu2 === A2_BTYPE || io.ctrl.sel_alu2 === A2_JTYPE
  val id_imm_l = io.ctrl.sel_alu2 === A2_LTYPE
  val id_imm_zero = io.ctrl.sel_alu2 === A2_ZERO || io.ctrl.sel_alu2 === A2_RTYPE
  val id_imm_ibz = io.ctrl.sel_alu2 === A2_ITYPE || io.ctrl.sel_alu2 === A2_BTYPE || id_imm_zero
  val id_imm_sign = Mux(id_imm_bj, id_inst(31),
                    Mux(id_imm_l, id_inst(26),
                    Mux(id_imm_zero, Bits(0,1),
                        id_inst(21)))) // IMM_ITYPE
  val id_imm_small = Mux(id_imm_zero, Bits(0,12),
                         Cat(Mux(id_imm_bj, id_inst(31,27), id_inst(21,17)), id_inst(16,10)))
  val id_imm = Cat(Fill(32, id_imm_sign),
                   Mux(id_imm_l, Cat(id_inst(26,7), Bits(0,12)),
                   Mux(id_imm_ibz, Cat(Fill(20, id_imm_sign), id_imm_small),
                       Cat(Fill(7, id_imm_sign), id_inst(31,7))))) // A2_JTYPE

  val id_op2_dmem_bypass = id_rs2_dmem_bypass && io.ctrl.sel_alu2 === A2_RTYPE
  val id_op2 = Mux(io.ctrl.sel_alu2 === A2_RTYPE, id_rs2, id_imm)

  io.ctrl.inst := id_inst
  io.fpu.inst := id_inst

  // execute stage
  ex_reg_pc             := id_pc
  ex_reg_inst           := id_inst
  ex_reg_raddr1         := id_raddr1
  ex_reg_raddr2         := id_raddr2;
  ex_reg_op2            := id_op2;
  ex_reg_rs2            := id_rs2;
  ex_reg_rs1            := id_rs1;
  ex_reg_waddr          := id_waddr;
  ex_reg_ctrl_fn_dw     := io.ctrl.fn_dw.toUFix;
  ex_reg_ctrl_fn_alu    := io.ctrl.fn_alu;
  ex_reg_ctrl_sel_wb    := io.ctrl.sel_wb;

  val ex_rs1 = Mux(Reg(id_rs1_dmem_bypass), wb_reg_dmem_wdata, ex_reg_rs1)
  val ex_rs2 = Mux(Reg(id_rs2_dmem_bypass), wb_reg_dmem_wdata, ex_reg_rs2)
  val ex_op2 = Mux(Reg(id_op2_dmem_bypass), wb_reg_dmem_wdata, ex_reg_op2)

  alu.io.dw    := ex_reg_ctrl_fn_dw;
  alu.io.fn    := ex_reg_ctrl_fn_alu;
  alu.io.in2   := ex_op2.toUFix
  alu.io.in1   := ex_rs1.toUFix

  io.fpu.fromint_data := ex_rs1
  
  // divider
  val div = new rocketDivider(earlyOut = true)
  div.io.req.valid := io.ctrl.div_val
  div.io.req.bits.fn := Cat(ex_reg_ctrl_fn_dw, io.ctrl.div_fn)
  div.io.req.bits.in0 := ex_rs1
  div.io.req.bits.in1 := ex_rs2
  div.io.req_tag := ex_reg_waddr
  div.io.req_kill := io.ctrl.div_kill
  div.io.resp_rdy := !dmem_resp_replay
  io.ctrl.div_rdy := div.io.req.ready
  io.ctrl.div_result_val := div.io.resp_val
  
  // multiplier
  var mul_io = new rocketMultiplier(unroll = 4, earlyOut = true).io
  if (HAVE_VEC)
  {
    val vu_mul = new rocketVUMultiplier(nwbq = 1)
    vu_mul.io.vu.req <> io.vec_imul_req
    vu_mul.io.vu.resp <> io.vec_imul_resp
    mul_io = vu_mul.io.cpu
  }
  mul_io.req.valid := io.ctrl.mul_val
  mul_io.req.bits.fn := Cat(ex_reg_ctrl_fn_dw, io.ctrl.mul_fn)
  mul_io.req.bits.in0 := ex_rs1
  mul_io.req.bits.in1 := ex_rs2
  mul_io.req_tag := ex_reg_waddr
  mul_io.req_kill := io.ctrl.mul_kill
  mul_io.resp_rdy := !dmem_resp_replay && !div.io.resp_val
  io.ctrl.mul_rdy := mul_io.req.ready
  io.ctrl.mul_result_val := mul_io.resp_val
  
  io.ctrl.ex_waddr := ex_reg_waddr; // for load/use hazard detection & bypass control

  // D$ request interface (registered inside D$ module)
  // other signals (req_val, req_rdy) connect to control module  
  io.dmem.req.bits.idx  := ex_effective_address
  io.dmem.req.bits.data := Mux(io.ctrl.mem_fp_val, io.fpu.store_data, mem_reg_rs2)
  io.dmem.req.bits.tag := Cat(ex_reg_waddr, io.ctrl.ex_fp_val)
  io.dtlb.vpn := ex_effective_address >> UFix(PGIDX_BITS)

	// processor control regfile read
  pcr.io.r.en   := io.ctrl.pcr != PCR_N
  pcr.io.r.addr := wb_reg_raddr1

  pcr.io.host <> io.host

  io.ctrl.irq_timer    := pcr.io.irq_timer;
  io.ctrl.irq_ipi      := pcr.io.irq_ipi;  
  io.ctrl.status       := pcr.io.status;
  io.ctrl.pcr_replay   := pcr.io.replay
  io.ptbr              := pcr.io.ptbr;
  io.ptbr_wen          := pcr.io.ptbr_wen;
  
	// branch resolution logic
  io.ctrl.jalr_eq := ex_reg_rs1 === id_pc.toFix && ex_reg_op2(id_imm_small.getWidth-1,0) === UFix(0)
  io.ctrl.br_eq   := (ex_rs1 === ex_rs2)
  io.ctrl.br_ltu  := (ex_rs1.toUFix < ex_rs2.toUFix)
  io.ctrl.br_lt :=
    (~(ex_rs1(63) ^ ex_rs2(63)) & io.ctrl.br_ltu |
    ex_rs1(63) & ~ex_rs2(63)).toBool

  // time stamp counter
  val tsc_reg = Reg(resetVal = UFix(0,64));
  tsc_reg := tsc_reg + UFix(1);
  // instructions retired counter
  val irt_reg = Reg(resetVal = UFix(0,64));
  when (io.ctrl.wb_valid) { irt_reg := irt_reg + UFix(1); }
  
	// writeback select mux
  ex_wdata :=
    Mux(ex_reg_ctrl_sel_wb === WB_PC,  ex_pc_plus4.toFix,
    Mux(ex_reg_ctrl_sel_wb === WB_TSC, tsc_reg,
    Mux(ex_reg_ctrl_sel_wb === WB_IRT, irt_reg,
        ex_alu_out))).toBits // WB_ALU

  // subword store data generation
  val storegen = new StoreDataGen
  storegen.io.typ := io.ctrl.ex_mem_type
  storegen.io.din  := ex_rs2
        
  // memory stage
  mem_reg_pc                := ex_reg_pc;
  mem_reg_inst              := ex_reg_inst
  mem_reg_rs2               := storegen.io.dout
  mem_reg_waddr             := ex_reg_waddr;
  mem_reg_wdata             := ex_wdata;
  mem_reg_raddr1            := ex_reg_raddr1
  mem_reg_raddr2            := ex_reg_raddr2;
  
  // for load/use hazard detection (load byte/halfword)
  io.ctrl.mem_waddr := mem_reg_waddr;

  // 32/64 bit load handling (moved to earlier in file)
      
  // writeback arbitration
  val dmem_resp_xpu = !io.dmem.resp.bits.tag(0).toBool
  val dmem_resp_fpu =  io.dmem.resp.bits.tag(0).toBool
  val dmem_resp_waddr = io.dmem.resp.bits.tag.toUFix >> UFix(1)
  dmem_resp_replay := io.dmem.resp.bits.replay && dmem_resp_xpu;
  r_dmem_resp_replay  := dmem_resp_replay
  r_dmem_resp_waddr   := dmem_resp_waddr
  r_dmem_fp_replay    := io.dmem.resp.bits.replay && dmem_resp_fpu;

  val mem_ll_waddr = Mux(dmem_resp_replay, dmem_resp_waddr,
                     Mux(div.io.resp_val, div.io.resp_tag,
                     Mux(mul_io.resp_val, mul_io.resp_tag,
                         mem_reg_waddr))).toUFix
  val mem_ll_wdata = Mux(div.io.resp_val, div.io.resp_bits,
                     Mux(mul_io.resp_val, mul_io.resp_bits,
                     Mux(io.ctrl.mem_fp_val && io.ctrl.mem_wen, io.fpu.toint_data,
                         mem_reg_wdata)))
  val mem_ll_wb = dmem_resp_replay || div.io.resp_val || mul_io.resp_val

  io.fpu.dmem_resp_val := io.dmem.resp.valid && dmem_resp_fpu
  io.fpu.dmem_resp_data := io.dmem.resp.bits.data
  io.fpu.dmem_resp_type := io.dmem.resp.bits.typ
  io.fpu.dmem_resp_tag := dmem_resp_waddr

  // writeback stage
  wb_reg_pc             := mem_reg_pc;
  wb_reg_inst           := mem_reg_inst
  wb_reg_ll_wb          := mem_ll_wb
  wb_reg_rs2            := mem_reg_rs2
  wb_reg_waddr          := mem_ll_waddr
  wb_reg_wdata          := mem_ll_wdata
  wb_reg_dmem_wdata     := io.dmem.resp.bits.data
  wb_reg_vec_waddr      := mem_reg_waddr
  wb_reg_vec_wdata      := mem_reg_wdata
  wb_reg_raddr1         := mem_reg_raddr1
  wb_reg_raddr2         := mem_reg_raddr2;

  // regfile write
  val wb_src_dmem = Reg(io.ctrl.mem_load) && io.ctrl.wb_valid || r_dmem_resp_replay

  if (HAVE_VEC)
  {
    // vector datapath
    val vec = new rocketDpathVec()

    vec.io.ctrl <> io.vec_ctrl
    io.vec_iface <> vec.io.iface 

    vec.io.valid := io.ctrl.wb_valid && pcr.io.status(SR_EV)
    vec.io.inst := wb_reg_inst
    vec.io.waddr := wb_reg_vec_waddr
    vec.io.raddr1 := wb_reg_raddr1
    vec.io.vecbank := pcr.io.vecbank
    vec.io.vecbankcnt := pcr.io.vecbankcnt
    vec.io.wdata := wb_reg_vec_wdata
    vec.io.rs2 := wb_reg_rs2

    pcr.io.vec_irq_aux := vec.io.irq_aux
    pcr.io.vec_appvl := vec.io.appvl
    pcr.io.vec_nxregs := vec.io.nxregs
    pcr.io.vec_nfregs := vec.io.nfregs

    wb_wdata :=
      Mux(vec.io.wen, Cat(Bits(0,52), vec.io.appvl),
      Mux(wb_src_dmem, io.dmem.resp.bits.data_subword,
          wb_reg_wdata))
  }
  else
  {
    pcr.io.vec_irq_aux := UFix(0)
    pcr.io.vec_appvl := UFix(0)
    pcr.io.vec_nxregs := UFix(0)
    pcr.io.vec_nfregs := UFix(0)

    wb_wdata :=
      Mux(wb_src_dmem, io.dmem.resp.bits.data_subword,
          wb_reg_wdata)
  }

  rfile.io.w0.addr := wb_reg_waddr
  rfile.io.w0.en   := io.ctrl.wb_wen || wb_reg_ll_wb
  rfile.io.w0.data := Mux(io.ctrl.pcr != PCR_N && io.ctrl.wb_wen, pcr.io.r.data, wb_wdata)

  io.ctrl.wb_waddr := wb_reg_waddr
  io.ctrl.mem_wb := dmem_resp_replay;

  // scoreboard clear (for div/mul and D$ load miss writebacks)
  io.ctrl.sboard_clr   := mem_ll_wb
  io.ctrl.sboard_clra  := mem_ll_waddr
  io.ctrl.fp_sboard_clr  := r_dmem_fp_replay
  io.ctrl.fp_sboard_clra := r_dmem_resp_waddr
  io.ctrl.fp_sboard_wb_waddr := Reg(mem_reg_waddr)

	// processor control regfile write
  pcr.io.w.addr := wb_reg_raddr1
  pcr.io.w.en   := io.ctrl.pcr === PCR_T || io.ctrl.pcr === PCR_S || io.ctrl.pcr === PCR_C
  pcr.io.w.data := Mux(io.ctrl.pcr === PCR_S, pcr.io.r.data | wb_reg_wdata,
                   Mux(io.ctrl.pcr === PCR_C, pcr.io.r.data & ~wb_reg_wdata,
                   wb_reg_wdata))

  pcr.io.eret      	  := io.ctrl.wb_eret;
  pcr.io.exception 	  := io.ctrl.exception;
  pcr.io.cause 			  := io.ctrl.cause;
  pcr.io.pc					  := wb_reg_pc;
  pcr.io.badvaddr_wen := io.ctrl.badvaddr_wen;
  pcr.io.vec_irq_aux_wen := io.ctrl.vec_irq_aux_wen
}
