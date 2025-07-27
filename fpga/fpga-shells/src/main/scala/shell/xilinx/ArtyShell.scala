package sifive.fpgashells.shell.xilinx.artyshell

import chisel3._
import chisel3.experimental.Analog
import freechips.rocketchip.devices.debug._
import freechips.rocketchip.subsystem.{PBUS, Attachable}
import org.chipsalliance.cde.config._
import sifive.blocks.devices.pinctrl.BasePin
import sifive.blocks.devices.spi._
import sifive.blocks.devices.uart._
import sifive.fpgashells.ip.xilinx._

//-------------------------------------------------------------------------
// ArtyShell
//-------------------------------------------------------------------------

abstract class ArtyShell(implicit val p: Parameters) extends RawModule {

  //-----------------------------------------------------------------------
  // Interface
  //-----------------------------------------------------------------------

  // Clock & Reset
  val CLK100MHZ    = IO(Input(Clock()))
  val ck_rst       = IO(Input(Bool()))

  // Green LEDs
  val led_0        = IO(Analog(1.W))
  val led_1        = IO(Analog(1.W))
  val led_2        = IO(Analog(1.W))
  val led_3        = IO(Analog(1.W))

  // RGB LEDs, 3 pins each
  val led0_r       = IO(Analog(1.W))
  val led0_g       = IO(Analog(1.W))
  val led0_b       = IO(Analog(1.W))

  val led1_r       = IO(Analog(1.W))
  val led1_g       = IO(Analog(1.W))
  val led1_b       = IO(Analog(1.W))

  val led2_r       = IO(Analog(1.W))
  val led2_g       = IO(Analog(1.W))
  val led2_b       = IO(Analog(1.W))

  // Sliding switches
  val sw_0         = IO(Analog(1.W))
  val sw_1         = IO(Analog(1.W))
  val sw_2         = IO(Analog(1.W))
  val sw_3         = IO(Analog(1.W))

  // Buttons. First 3 used as GPIO, the last is used as wakeup
  val btn_0        = IO(Analog(1.W))
  val btn_1        = IO(Analog(1.W))
  val btn_2        = IO(Analog(1.W))
  val btn_3        = IO(Analog(1.W))

  // Dedicated QSPI interface
  val qspi_cs      = IO(Analog(1.W))
  val qspi_sck     = IO(Analog(1.W))
  val qspi_dq      = IO(Vec(4, Analog(1.W)))

  // UART0
  val uart_rxd_out = IO(Analog(1.W))
  val uart_txd_in  = IO(Analog(1.W))

  // PMOD header - JA (used for more generic GPIOs)
  val ja_0         = IO(Analog(1.W))
  val ja_1         = IO(Analog(1.W))
  val ja_2         = IO(Analog(1.W))
  val ja_3         = IO(Analog(1.W))
  val ja_4         = IO(Analog(1.W))
  val ja_5         = IO(Analog(1.W))
  val ja_6         = IO(Analog(1.W))
  val ja_7         = IO(Analog(1.W))

  // PMOD header - JB (used for more generic GPIOs)
  val jb_0         = IO(Analog(1.W))
  val jb_1         = IO(Analog(1.W))
  val jb_2         = IO(Analog(1.W))
  val jb_3         = IO(Analog(1.W))
  val jb_4         = IO(Analog(1.W))
  val jb_5         = IO(Analog(1.W))
  val jb_6         = IO(Analog(1.W))
  val jb_7         = IO(Analog(1.W))

  // PMOD header - JC (used for Serial TileLink connection)
  val jc_0         = IO(Analog(1.W))
  val jc_1         = IO(Analog(1.W))
  val jc_2         = IO(Analog(1.W))
  val jc_3         = IO(Analog(1.W))
  val jc_4         = IO(Analog(1.W))
  val jc_5         = IO(Analog(1.W))
  val jc_6         = IO(Analog(1.W))
  val jc_7         = IO(Analog(1.W))

  // PMOD header - JD (used for debugger connection)
  val jd_0         = IO(Analog(1.W))  // TDO
  val jd_1         = IO(Analog(1.W))  // nTRST
  val jd_2         = IO(Analog(1.W))  // TCK
  val jd_3         = IO(Analog(1.W))  // TXD
  val jd_4         = IO(Analog(1.W))  // TDI
  val jd_5         = IO(Analog(1.W))  // TMS
  val jd_6         = IO(Analog(1.W))  // nSRST
  val jd_7         = IO(Analog(1.W))  // RXD

  // ChipKit Digital I/O Pins
  val ck_io        = IO(Vec(20, Analog(1.W)))

  // ChipKit SPI
  val ck_miso      = IO(Analog(1.W))
  val ck_mosi      = IO(Analog(1.W))
  val ck_ss        = IO(Analog(1.W))
  val ck_sck       = IO(Analog(1.W))

  //-----------------------------------------------------------------------
  // Wire declrations
  //-----------------------------------------------------------------------

  // Note: these frequencies are approximate.
  val clock_8MHz     = Wire(Clock())
  val clock_32MHz    = Wire(Clock())
  val clock_65MHz    = Wire(Clock())

  val mmcm_locked    = Wire(Bool())

  val reset_core     = Wire(Bool())
  val reset_bus      = Wire(Bool())
  val reset_periph   = Wire(Bool())
  val reset_intcon_n = Wire(Bool())
  val reset_periph_n = Wire(Bool())

  val SRST_n         = Wire(Bool())

  val dut_jtag_TCK   = Wire(Clock())
  val dut_jtag_TMS   = Wire(Bool())
  val dut_jtag_TDI   = Wire(Bool())
  val dut_jtag_TDO   = Wire(Bool())
  val dut_jtag_reset = Wire(Bool())
  val dut_ndreset    = Wire(Bool())

  //-----------------------------------------------------------------------
  // Clock Generator
  //-----------------------------------------------------------------------
  // Mixed-mode clock generator

  val ip_mmcm = Module(new mmcm())

  ip_mmcm.io.clk_in1 := CLK100MHZ
  clock_8MHz         := ip_mmcm.io.clk_out1  // 8.388 MHz = 32.768 kHz * 256
  clock_65MHz        := ip_mmcm.io.clk_out2  // 65 Mhz
  clock_32MHz        := ip_mmcm.io.clk_out3  // 65/2 Mhz
  ip_mmcm.io.resetn  := ck_rst
  mmcm_locked        := ip_mmcm.io.locked

  //-----------------------------------------------------------------------
  // System Reset
  //-----------------------------------------------------------------------
  // processor system reset module

  val ip_reset_sys = Module(new reset_sys())

  ip_reset_sys.io.slowest_sync_clk := clock_8MHz
  ip_reset_sys.io.ext_reset_in     := ck_rst & SRST_n
  ip_reset_sys.io.aux_reset_in     := true.B
  ip_reset_sys.io.mb_debug_sys_rst := dut_ndreset
  ip_reset_sys.io.dcm_locked       := mmcm_locked

  reset_core                       := ip_reset_sys.io.mb_reset
  reset_bus                        := ip_reset_sys.io.bus_struct_reset
  reset_periph                     := ip_reset_sys.io.peripheral_reset
  reset_intcon_n                   := ip_reset_sys.io.interconnect_aresetn
  reset_periph_n                   := ip_reset_sys.io.peripheral_aresetn

  //-----------------------------------------------------------------------
  // SPI Flash
  //-----------------------------------------------------------------------

  def connectSPIFlash(dut: HasPeripherySPIFlash): Unit = dut.qspi.headOption.foreach {
    val pbus = dut.asInstanceOf[Attachable].locateTLBusWrapper(PBUS)
    connectSPIFlash(_, pbus.module.clock, pbus.module.reset.asBool)
  }

  def connectSPIFlash(qspi: SPIPortIO, clock: Clock, reset: Bool): Unit = {
    val qspi_pins = Wire(new SPIPins(() => {new BasePin()}, qspi.c))

    SPIPinsFromPort(qspi_pins, qspi, clock, reset, syncStages = qspi.c.defaultSampleDel)

    IOBUF(qspi_sck, qspi.sck)
    IOBUF(qspi_cs,  qspi.cs(0))

    (qspi_dq zip qspi_pins.dq).foreach { case(a, b) => IOBUF(a, b) }
  }

  //---------------------------------------------------------------------
  // Debug JTAG
  //---------------------------------------------------------------------

  def connectDebugJTAG(dut: HasPeripheryDebug): SystemJTAGIO = {

    require(dut.debug.isDefined, "Connecting JTAG requires that debug module exists")
    //-------------------------------------------------------------------
    // JTAG Reset
    //-------------------------------------------------------------------

    val jtag_power_on_reset = PowerOnResetFPGAOnly(clock_32MHz)

    dut_jtag_reset := jtag_power_on_reset

    //-------------------------------------------------------------------
    // JTAG IOBUFs
    //-------------------------------------------------------------------

    dut_jtag_TCK  := IBUFG(IOBUF(jd_2).asClock)

    dut_jtag_TMS  := IOBUF(jd_5)
    PULLUP(jd_5)

    dut_jtag_TDI  := IOBUF(jd_4)
    PULLUP(jd_4)

    IOBUF(jd_0, dut_jtag_TDO)

    SRST_n := IOBUF(jd_6)
    PULLUP(jd_6)

    //-------------------------------------------------------------------
    // JTAG PINS
    //-------------------------------------------------------------------

    val djtag     = dut.debug.get.systemjtag.get

    djtag.jtag.TCK := dut_jtag_TCK
    djtag.jtag.TMS := dut_jtag_TMS
    djtag.jtag.TDI := dut_jtag_TDI
    dut_jtag_TDO   := djtag.jtag.TDO.data

    djtag.mfr_id   := p(JtagDTMKey).idcodeManufId.U(11.W)
    djtag.part_number := p(JtagDTMKey).idcodePartNum.U(16.W)
    djtag.version  := p(JtagDTMKey).idcodeVersion.U(4.W)

    djtag.reset    := dut_jtag_reset
    dut_ndreset    := dut.debug.get.ndreset

    djtag
  }

  //---------------------------------------------------------------------
  // UART
  //---------------------------------------------------------------------

  def connectUART(dut: HasPeripheryUART): Unit = dut.uart.headOption.foreach(connectUART)

  def connectUART(uart: UARTPortIO): Unit = {
    IOBUF(uart_rxd_out, uart.txd)
    uart.rxd := IOBUF(uart_txd_in)
  }

}

/*
   Copyright 2016 SiFive, Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
