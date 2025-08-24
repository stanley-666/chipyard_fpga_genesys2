package chipyard.fpga.genesys2

import chisel3._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.diplomacy.{LazyModule, LazyRawModuleImp, BundleBridgeSource}
import org.chipsalliance.cde.config.{Parameters}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy.{IdRange, TransferSizes}
import freechips.rocketchip.subsystem.{SystemBusKey}
import freechips.rocketchip.prci._
import sifive.fpgashells.shell.xilinx._
import sifive.fpgashells.ip.xilinx.{IBUF, PowerOnResetFPGAOnly}
import sifive.fpgashells.shell._
import sifive.fpgashells.clocks._

import sifive.blocks.devices.uart.{PeripheryUARTKey, UARTPortIO}
import sifive.blocks.devices.spi.{PeripherySPIKey, SPIPortIO}

import chipyard._
import chipyard.harness._
// top-level imports
// case object FPGAFrequencyKey extends Field[Double](60.0)
class DDR3GENESYS2ShellPlacer(shell: GENESYS2FPGATestHarness, val shellInput: DDRShellInput)(implicit val valName: ValName)
  extends DDRShellPlacer[GENESYS2FPGATestHarness] {
  def place(designInput: DDRDesignInput) = new DDRGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

class GENESYS2FPGATestHarness(override implicit val p: Parameters) extends GENESYS2ShellBasicOverlays {

  def dp = designParameters

  val pmod_is_sdio  = p(GENESYS2ShellPMOD) == "SDIO"
  val jtag_location = Some(if (pmod_is_sdio) "FMC_J2" else "PMOD_J52")

  // Order matters; ddr depends on sys_clock
  val uart      = Overlay(UARTOverlayKey, new UARTGENESYS2ShellPlacer(this, UARTShellInput()))

  val sdio      = if (pmod_is_sdio) Some(Overlay(SPIOverlayKey, new SDIOGENESYS2ShellPlacer(this, SPIShellInput()))) else None
  val jtag      = Overlay(JTAGDebugOverlayKey, new JTAGDebugGENESYS2ShellPlacer(this, JTAGDebugShellInput(location = jtag_location)))
  //val cjtag     = Overlay(cJTAGDebugOverlayKey, new cJTAGDebugGENESYS2ShellPlacer(this, cJTAGDebugShellInput()))
  //val jtagBScan = Overlay(JTAGDebugBScanOverlayKey, new JTAGDebugBScanGENESYS2ShellPlacer(this, JTAGDebugBScanShellInput()))
  //val ddr3      = Overlay(DDROverlayKey, new DDR3GENESYS2ShellPlacer(this, DDRShellInput()))
  
  //val topDesign = LazyModule(p(BuildTop)(dp)).suggestName("chiptop")

// DOC include start: ClockOverlay
  // place all clocks in the shell
  println(s"ClockInputOverlayKey = ${dp(ClockInputOverlayKey)}")
  require(dp(ClockInputOverlayKey).nonEmpty, "ClockInputOverlayKey is empty!")
  require(dp(ClockInputOverlayKey).size >= 1)
  val sysClkNode = dp(ClockInputOverlayKey)(0).place(ClockInputDesignInput()).overlayOutput.node

  /*** Connect/Generate clocks ***/

  // connect to the PLL that will generate multiple clocks
  println(s"PLLFactoryKey = ${dp(PLLFactoryKey)}")
  //require(dp(PLLFactoryKey).nonEmpty, "PLLFactoryKey is empty!")
  val harnessSysPLL = dp(PLLFactoryKey)()
  harnessSysPLL := sysClkNode

  // create and connect to the dutClock
  println(s"SystemBusKey = ${dp(SystemBusKey)}")
  //require(dp(SystemBusKey).nonEmpty, "SystemBusKey is empty!")
  val dutFreqMHz = (dp(SystemBusKey).dtsFrequency.get / (1000 * 1000)).toInt
  val dutClock = ClockSinkNode(freqMHz = dutFreqMHz)
  println(s"Genesys2 FPGA Base Clock Freq: ${dutFreqMHz} MHz")
  val dutWrangler = LazyModule(new ResetWrangler)
  val dutGroup = ClockGroup()
  dutClock := dutWrangler.node := dutGroup := harnessSysPLL
// DOC include end: ClockOverlay

 /*** UART ***/

// DOC include start: UartOverlay
  // 1st UART goes to the VCU118 dedicated UART

  val io_uart_bb = BundleBridgeSource(() => (new UARTPortIO(dp(PeripheryUARTKey).head)))
  dp(UARTOverlayKey).head.place(UARTDesignInput(io_uart_bb))
// DOC include end: UartOverlay

  /*** SPI ***/

  // 1st SPI goes to the VCU118 SDIO port

  val io_spi_bb = BundleBridgeSource(() => (new SPIPortIO(dp(PeripherySPIKey).head)))
  println(s"SPIOverlayKey = ${dp(SPIOverlayKey)}")
  require(dp(SPIOverlayKey).nonEmpty, "SPIOverlayKey is empty!")
  println(s"PeripherySPIKey = ${dp(PeripherySPIKey)}")
  require(dp(PeripherySPIKey).nonEmpty, "PeripherySPIKey is empty!")
  dp(SPIOverlayKey).head.place(SPIDesignInput(dp(PeripherySPIKey).head, io_spi_bb))

  /*** DDR ***/

  val ddrNode = dp(DDROverlayKey).head.place(DDRDesignInput(dp(ExtTLMem).get.master.base, dutWrangler.node, harnessSysPLL)).overlayOutput.ddr

  // connect 1 mem. channel to the FPGA DDR
  val ddrClient = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
    name = "chip_ddr",
    sourceId = IdRange(0, 1 << dp(ExtTLMem).get.master.idBits)
  )))))
  ddrNode := TLWidthWidget(dp(ExtTLMem).get.master.beatBytes) := ddrClient
  
  /*** JTAG ***/
  println(s"JTAGDebugOverlayKey = ${dp(JTAGDebugOverlayKey)}")
  require(dp(JTAGDebugOverlayKey).nonEmpty, "JTAGDebugOverlayKey is empty!")
  val jtagPlacedOverlay = dp(JTAGDebugOverlayKey).head.place(JTAGDebugDesignInput())

  // module implementation
  override lazy val module = new GENESYS2FPGATestHarnessImp(this)
}

class GENESYS2FPGATestHarnessImp(_outer: GENESYS2FPGATestHarness) extends LazyRawModuleImp(_outer) with HasHarnessInstantiators {
  override def provideImplicitClockToLazyChildren = true
  val genesys2Outer = _outer
  val reset = IO(Input(Bool()))
  reset.suggestName("reset")  //（其實 assign val 就會自動命名，不加這行也 OK）
  _outer.xdc.addPackagePin(reset, "R19")  //pull up to 3.3v
  _outer.xdc.addIOStandard(reset, "LVCMOS33")

  val resetIBUF = Module(new IBUF)
  resetIBUF.io.I := ~reset 

  val sysclk: Clock = _outer.sysClkNode.out.head._1.clock

  val powerOnReset: Bool = PowerOnResetFPGAOnly(sysclk)
  _outer.sdc.addAsyncPath(Seq(powerOnReset))

  //有chiplink
  /*
  val ereset: Bool = _outer.chiplink.get() match {
    case Some(x: ChipLinkGENESYS2PlacedOverlay) => !x.ereset_n
    case _ => false.B
  }
  
  _outer.pllReset := (resetIBUF.io.O || powerOnReset || ereset)
  */

  // 沒chiplink
  _outer.pllReset := (resetIBUF.io.O || powerOnReset)

  // reset setup
  val hReset = Wire(Reset())
  hReset := _outer.dutClock.in.head._1.reset

  def referenceClockFreqMHz = _outer.dutFreqMHz
  def referenceClock = _outer.dutClock.in.head._1.clock
  def referenceReset = hReset
  def success = { require(false, "Unused"); false.B }

  childClock := referenceClock
  childReset := referenceReset
  /*
  // harness binders are non-lazy
  _outer.topDesign match { case d: HasTestHarnessFunctions =>
    d.harnessFunctions.foreach(_(this))
  }
  _outer.topDesign match { case d: HasIOBinders =>
    ApplyHarnessBinders(this, d.lazySystem, d.portMap)
  }

  //rst from osd
  val pllLocked = _outer.pllFactory.plls.getWrappedValue(0)._1.getLocked
  var sys_rst = WireInit((~pllLocked).asInstanceOf[Reset]) //getLocked active high
  if(_outer.topDesign.asInstanceOf[ChipTop].lazySystem.isInstanceOf[freechips.rocketchip.osd.HasOSDMAM]) {
    _outer.topDesign.module.asInstanceOf[LazyRawModuleImp].getPorts.foreach { ports =>
      if(ports.id.toNamed.name == "sys_rst") { sys_rst = WireInit((ports.id.asInstanceOf[Reset].asBool() || ~pllLocked).asInstanceOf[Reset]) }
  }}
  if(_outer.topDesign.asInstanceOf[ChipTop].lazySystem.isInstanceOf[freechips.rocketchip.osd.HasOSDMAM]) {
    _outer.topDesign.module.asInstanceOf[LazyRawModuleImp].getPorts.foreach { ports =>
      if(ports.id.isInstanceOf[AsyncReset])   { ports.id.asInstanceOf[Reset] := sys_rst.asAsyncReset() }
      if(ports.id.toNamed.name == "glip_rst") { ports.id := ~pllLocked } //getLocked active high
      if(_outer.ddrplaced.isInstanceOf[DDRGENESYS2PlacedOverlay]) {
        _outer.ddrplaced.asInstanceOf[DDRGENESYS2PlacedOverlay].mig.module.reset := sys_rst
      }
  }}

  //fan
  val fan_pwm = IO(Output(Bool()))
  _outer.xdc.addPackagePin(fan_pwm, "W19")  //pull up to 3.3v
  _outer.xdc.addIOStandard(fan_pwm, "LVCMOS33")
  fan_pwm := _outer.ddrplaced.asInstanceOf[DDRGENESYS2PlacedOverlay].mig.module.io.port.fan_pwm
  */
  instantiateChipTops()
}
