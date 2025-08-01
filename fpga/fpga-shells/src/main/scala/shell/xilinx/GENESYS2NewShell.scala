// See LICENSE for license details.
package sifive.fpgashells.shell.xilinx

import chisel3._
import chisel3.experimental.{Analog, attach}
import chisel3.experimental.dataview._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.prci._
import org.chipsalliance.cde.config._
import sifive.fpgashells.clocks._
import sifive.fpgashells.devices.xilinx.xilinxgenesys2mig._
import sifive.fpgashells.ip.xilinx._
import sifive.fpgashells.shell._
import sifive.fpgashells.shell.xilinx._
import sifive.fpgashells.ip.xilinx.genesys2mig.GENESYS2MIGIODDR


// ----------- SysClock ------------
class SysClockGENESYS2PlacedOverlay(val shell: GENESYS2ShellBasicOverlays, name: String, val designInput: ClockInputDesignInput, val shellInput: ClockInputShellInput)
  extends LVDSClockInputXilinxPlacedOverlay(name, designInput, shellInput) {
  val node = shell { ClockSourceNode(freqMHz = 200, jitterPS = 50)(ValName(name)) }
  shell { InModuleBody {
    shell.xdc.addPackagePin(io.p, "AD12")
    shell.xdc.addPackagePin(io.n, "AD11")
    shell.xdc.addIOStandard(io.p, "LVDS")
    shell.xdc.addIOStandard(io.n, "LVDS")
  }}
}
class SysClockGENESYS2ShellPlacer(shell: GENESYS2ShellBasicOverlays, val shellInput: ClockInputShellInput)(implicit val valName: ValName)
  extends ClockInputShellPlacer[GENESYS2ShellBasicOverlays] {
  def place(designInput: ClockInputDesignInput) =
    new SysClockGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

// ----------- RefClock ------------
class RefClockGENESYS2PlacedOverlay(val shell: GENESYS2ShellBasicOverlays, name: String, val designInput: ClockInputDesignInput, val shellInput: ClockInputShellInput)
  extends LVDSClockInputXilinxPlacedOverlay(name, designInput, shellInput) {
  val node = shell { ClockSourceNode(freqMHz = 125, jitterPS = 50)(ValName(name)) }

  shell { InModuleBody {
    shell.xdc.addPackagePin(io.p, "AY24")
    shell.xdc.addPackagePin(io.n, "AY23")
    shell.xdc.addIOStandard(io.p, "LVDS")
    shell.xdc.addIOStandard(io.n, "LVDS")
  } }
}
class RefClockGENESYS2ShellPlacer(shell: GENESYS2ShellBasicOverlays, val shellInput: ClockInputShellInput)(implicit val valName: ValName)
  extends ClockInputShellPlacer[GENESYS2ShellBasicOverlays] {
  def place(designInput: ClockInputDesignInput) = new RefClockGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

// ----------- SDIO ------------
class SDIOGENESYS2PlacedOverlay(val shell: GENESYS2ShellBasicOverlays, name: String, val designInput: SPIDesignInput, val shellInput: SPIShellInput)
  extends SDIOXilinxPlacedOverlay(name, designInput, shellInput)
{
  shell { InModuleBody {
    val packagePinsWithPackageIOs = Seq(("R28", IOPin(io.spi_clk)), // clk
                                        ("R29", IOPin(io.spi_cs)), // sdio.cmd
                                        ("R26", IOPin(io.spi_dat(0))), 
                                        ("R30", IOPin(io.spi_dat(1))),
                                        ("P29", IOPin(io.spi_dat(2))),
                                        ("T30", IOPin(io.spi_dat(3))))

    // 略 "AE24" sdio_reset, "P28" sdio_cd (card_detect) 這兩個腳位
    packagePinsWithPackageIOs foreach { case (pin, io) => {
      shell.xdc.addPackagePin(io, pin)
      shell.xdc.addIOStandard(io, "LVCMOS33")
    } }
    packagePinsWithPackageIOs drop 1 foreach { case (pin, io) => {
      shell.xdc.addPullup(io)
      shell.xdc.addIOB(io)
    } }
  } }
}
class SDIOGENESYS2ShellPlacer(shell: GENESYS2ShellBasicOverlays, val shellInput: SPIShellInput)(implicit val valName: ValName)
  extends SPIShellPlacer[GENESYS2ShellBasicOverlays] {
  def place(designInput: SPIDesignInput) = new SDIOGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

// ----------- DDR ------------
case object GENESYS2DDRSize extends Field[BigInt](0x40000000L * 1) // 1GB
case object GENESYS2ShellDDR extends Field[Boolean](true)
class WithNoGENESYS2ShellDDR extends Config((site, here, up) => {
  case GENESYS2ShellDDR => false
})

class DDRGENESYS2PlacedOverlay(val shell: GENESYS2ShellBasicOverlays, name: String, val designInput: DDRDesignInput, val shellInput: DDRShellInput)
  extends DDRPlacedOverlay[XilinxGENESYS2MIGPads](name, designInput, shellInput)
{
  val size = p(GENESYS2DDRSize)

  val migParams = XilinxGENESYS2MIGParams(address = AddressSet.misaligned(di.baseAddress, size))
  val mig = LazyModule(new XilinxGENESYS2MIG(migParams))
  //val ioNode = BundleBridgeSource(() => mig.module.io.cloneType)
  //val topIONode = shell { ioNode.makeSink() }
  val ddrUI     = shell { ClockSourceNode(freqMHz = 200) }
  val areset    = shell { ClockSinkNode(Seq(ClockSinkParameters())) }
  areset := designInput.wrangler := ddrUI

  def overlayOutput = DDROverlayOutput(ddr = mig.node)
  def ioFactory = new XilinxGENESYS2MIGPads(size)

  //InModuleBody { ioNode.bundle <> mig.module.io }

  shell { InModuleBody {
    require (shell.sys_clock.get.isDefined, "Use of DDRGENESYS2Overlay depends on SysClockGENESYS2Overlay")
    val (sys, _) = shell.sys_clock.get.get.overlayOutput.node.out(0)
    val (ui, _) = ddrUI.out(0)
    val (ar, _) = areset.in(0)
    val port = mig.module.io.port
    io <> port.viewAsSupertype(new GENESYS2MIGIODDR(mig.depth))
    ui.clock := port.ui_clk
    ui.reset := /*!port.mmcm_locked ||*/ port.ui_clk_sync_rst // check if this correct
    port.sys_clk_i := sys.clock.asUInt
    port.sys_rst := sys.reset // pllReset
    port.aresetn := !(ar.reset.asBool)

    // This was just copied from the SiFive example, but it's hard to follow.
    // The pins are emitted in the following order(order in ip/xilinx/genesys2/genesys2mig.scala: class GENESYS2MIGIODDR):
    // addr[0->14], ba[0->2], ras_n, cas_n, we_n, reset_n, ck_p[0], ck_n[0], cke[0], cs_n[0], dm[0->3], odt[0], dq[0->31], dqs_n[0->3], dqs_p[0->3]
    val allddrpins = Seq("AC12", "AE8", "AD8", "AC10", "AD9", "AA13", "AA10","AA11", "Y10", "Y11", "AB8", "AA8", "AB12", "AA12", "AH9", // addr[0->14]
      "AE9",  "AB10", "AC11",                                            // ba[0->2]
      "AE11", "AF11", "AG13", "AG5",                                     // ras_n, cas_n, we_n,  reset_n,
      "AB9",                                                             // ck_p[0]
      "AC9",                                                             // ck_n[0] 
      "AJ9",                                                             // cke_n[0] 
      "AH12",                                                            // cs_n[0]   
      "AD4",  "AF3",  "AH4",  "AF8",                                     // dm[0->3]
      "AK9",                                                             // odt[0]
      "AD3",  "AC2",  "AC1",  "AC5",  "AC4",  "AD6",  "AE6",  "AC7",  "AF2",  "AE1", "AF1",  "AE4",  "AE3",  "AE5",  "AF5",  "AF6", // dq[0->15]
      "AJ4",  "AH6",  "AH5",  "AH2",  "AJ2",  "AJ1",  "AK1",  "AJ3",  "AF7",  "AG7", "AJ6",  "AK6",  "AJ8",  "AK8",  "AK5",  "AK4", // dq[16->31]
      "AD1",  "AG3",  "AH1",  "AJ7",                                      // dqs_n[0->3]
      "AD2",  "AG4",  "AG2",  "AH7")                                      // dqs_p[0->3]
    
    //it's useless because genesys2mig.scala will set_property PACKAGE_PIN and IOSTANDARD
    //(IOPin.of(io) zip allddrpins) foreach { case (io, pin) => shell.xdc.addPackagePin(io, pin)}
  } }

  shell.sdc.addGroup(pins = Seq(mig.island.module.blackbox.io.ui_clk))
}

class DDRGENESYS2ShellPlacer(shell: GENESYS2ShellBasicOverlays, val shellInput: DDRShellInput)(implicit val valName: ValName)
  extends DDRShellPlacer[GENESYS2ShellBasicOverlays] {
  def place(designInput: DDRDesignInput) = new DDRGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

// ----------- 基本 DIO (LED, Switch, Button) ------------
object LEDGENESYS2PinConstraints { // Pin 自動mapping
  val pins = Seq("T28", "V19", "U30", "U29", "V20", "V26", "W24", "W23")
}
class LEDGENESYS2PlacedOverlay(
  val shell: GENESYS2ShellBasicOverlays, name: String,
  val designInput: LEDDesignInput, val shellInput: LEDShellInput)
  extends LEDXilinxPlacedOverlay(name, designInput, shellInput, packagePin = Some(LEDGENESYS2PinConstraints.pins(shellInput.number)), ioStandard = "LVCMOS33")
class LEDGENESYS2ShellPlacer(shell: GENESYS2ShellBasicOverlays, val shellInput: LEDShellInput)(implicit val valName: ValName)
  extends LEDShellPlacer[GENESYS2ShellBasicOverlays] {
  def place(designInput: LEDDesignInput) =
    new LEDGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

object ButtonGENESYS2PinConstraints {
  val pins = Seq("M20", "C19", "B19", "M19", "E18")
}
class ButtonGENESYS2PlacedOverlay(
  val shell: GENESYS2ShellBasicOverlays, name: String,
  val designInput: ButtonDesignInput, val shellInput: ButtonShellInput)
  extends ButtonXilinxPlacedOverlay(name, designInput, shellInput, packagePin = Some(ButtonGENESYS2PinConstraints.pins(shellInput.number)), ioStandard = "LVCMOS33")

class ButtonGENESYS2ShellPlacer(shell: GENESYS2ShellBasicOverlays, val shellInput: ButtonShellInput)(implicit val valName: ValName)
  extends ButtonShellPlacer[GENESYS2ShellBasicOverlays] {
  def place(designInput: ButtonDesignInput) = new ButtonGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

object SwitchGENESYS2PinConstraints {
  val pins = Seq("G19", "G25", "H24", "K19", "N19", "P19", "P26", "P27")
}
class SwitchGENESYS2PlacedOverlay(val shell: GENESYS2ShellBasicOverlays, name: String, val designInput: SwitchDesignInput, val shellInput: SwitchShellInput)
  extends SwitchXilinxPlacedOverlay(name, designInput, shellInput, packagePin = Some(SwitchGENESYS2PinConstraints.pins(shellInput.number)), ioStandard = "LVCMOS33")

class SwitchGENESYS2ShellPlacer(shell: GENESYS2ShellBasicOverlays, val shellInput: SwitchShellInput)(implicit val valName: ValName)
  extends SwitchShellPlacer[GENESYS2ShellBasicOverlays] {
  def place(designInput: SwitchDesignInput) = new SwitchGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

// ----------- ChipLink ------------ TODO
class ChipLinkGENESYS2PlacedOverlay(val shell: GENESYS2ShellBasicOverlays, name: String, val designInput: ChipLinkDesignInput, val shellInput: ChipLinkShellInput)
  extends ChipLinkXilinxPlacedOverlay(name, designInput, shellInput, rxPhase= -120, txPhase= -90, rxMargin=0.6, txMargin=0.5)
{
  val ereset_n = shell { InModuleBody {
    val ereset_n = IO(Analog(1.W))
    ereset_n.suggestName("ereset_n")
    val pin = IOPin(ereset_n, 0)
    shell.xdc.addPackagePin(pin, "BC8")
    shell.xdc.addIOStandard(pin, "LVCMOS18")
    shell.xdc.addTermination(pin, "NONE")
    shell.xdc.addPullup(pin)

    val iobuf = Module(new IOBUF)
    iobuf.suggestName("chiplink_ereset_iobuf")
    attach(ereset_n, iobuf.io.IO)
    iobuf.io.T := true.B // !oe
    iobuf.io.I := false.B

    iobuf.io.O
  } }

  shell { InModuleBody {
    val dir1 = Seq("BC9", "AV8", "AV9", /* clk, rst, send */
                   "AY9",  "BA9",  "BF10", "BF9",  "BC11", "BD11", "BD12", "BE12",
                   "BF12", "BF11", "BE14", "BF14", "BD13", "BE13", "BC15", "BD15",
                   "BE15", "BF15", "BA14", "BB14", "BB13", "BB12", "BA16", "BA15",
                   "BC14", "BC13", "AY8",  "AY7",  "AW8",  "AW7",  "BB16", "BC16")
    val dir2 = Seq("AV14", "AK13", "AK14", /* clk, rst, send */
                   "AR14", "AT14", "AP12", "AR12", "AW12", "AY12", "AW11", "AY10",
                   "AU11", "AV11", "AW13", "AY13", "AN16", "AP16", "AP13", "AR13",
                   "AT12", "AU12", "AK15", "AL15", "AL14", "AM14", "AV10", "AW10",
                   "AN15", "AP15", "AK12", "AL12", "AM13", "AM12", "AJ13", "AJ12")
    (IOPin.of(io.b2c) zip dir1) foreach { case (io, pin) => shell.xdc.addPackagePin(io, pin) }
    (IOPin.of(io.c2b) zip dir2) foreach { case (io, pin) => shell.xdc.addPackagePin(io, pin) }
  } }
}
class ChipLinkGENESYS2ShellPlacer(shell: GENESYS2ShellBasicOverlays, val shellInput: ChipLinkShellInput)(implicit val valName: ValName)
  extends ChipLinkShellPlacer[GENESYS2ShellBasicOverlays] {
  def place(designInput: ChipLinkDesignInput) = new ChipLinkGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}
// END CHIPLINK


// ----------- 系統 Shell 抽象類 ------------
abstract class GENESYS2ShellBasicOverlays()(implicit p: Parameters) extends Series7Shell{

  // extend series7 pll
  // PLL reset causes
  val pllReset = InModuleBody { Wire(Bool()) }

  val sys_clock = Overlay(ClockInputOverlayKey, new SysClockGENESYS2ShellPlacer(this, ClockInputShellInput()))
  val ref_clock = Overlay(ClockInputOverlayKey, new RefClockGENESYS2ShellPlacer(this, ClockInputShellInput()))
  val led       = Seq.tabulate(8)(i => Overlay(LEDOverlayKey, new LEDGENESYS2ShellPlacer(this, LEDShellInput(color = "red", number = i))(valName = ValName(s"led_$i"))))
  val switch    = Seq.tabulate(4)(i => Overlay(SwitchOverlayKey, new SwitchGENESYS2ShellPlacer(this, SwitchShellInput(number = i))(valName = ValName(s"switch_$i"))))
  val button    = Seq.tabulate(5)(i => Overlay(ButtonOverlayKey, new ButtonGENESYS2ShellPlacer(this, ButtonShellInput(number = i))(valName = ValName(s"button_$i"))))
  //// Order matters; ddr depends on sys_clock
  val ddr       = Overlay(DDROverlayKey, new DDRGENESYS2ShellPlacer(this, DDRShellInput()))
  //val qsfp1     = Overlay(EthernetOverlayKey, new QSFP1GENESYS2ShellPlacer(this, EthernetShellInput()))
  //val qsfp2     = Overlay(EthernetOverlayKey, new QSFP2GENESYS2ShellPlacer(this, EthernetShellInput()))
  val chiplink  = Overlay(ChipLinkOverlayKey, new ChipLinkGENESYS2ShellPlacer(this, ChipLinkShellInput()))
  //val spi_flash = Overlay(SPIFlashOverlayKey, new SPIFlashGENESYS2ShellPlacer(this, SPIFlashShellInput()))
  //SPI Flash not functional
}

// ----------- JTAG ------------
case object GENESYS2ShellPMOD extends Field[String]("JTAG")
case object GENESYS2ShellPMOD2 extends Field[String]("JTAG")

class WithGENESYS2ShellPMOD(device: String) extends Config((site, here, up) => {
  case GENESYS2ShellPMOD => device
})

// Change JTAG pinouts to GENESYS2 J53
// Due to the level shifter is from 1.2V to 3.3V, the frequency of JTAG should be slow down to 1Mhz
class WithGENESYS2ShellPMOD2(device: String) extends Config((site, here, up) => {
  case GENESYS2ShellPMOD2 => device
})

class WithGENESYS2ShellPMODJTAG extends WithGENESYS2ShellPMOD("JTAG")
class WithGENESYS2ShellPMODSDIO extends WithGENESYS2ShellPMOD("SDIO")

// Reassign JTAG pinouts location to PMOD J53
class WithGENESYS2ShellPMOD2JTAG extends WithGENESYS2ShellPMOD2("PMODJ53_JTAG")

// TODO: JTAG is untested
class JTAGDebugGENESYS2PlacedOverlay(val shell: GENESYS2ShellBasicOverlays, name: String, val designInput: JTAGDebugDesignInput, val shellInput: JTAGDebugShellInput)
  extends JTAGDebugXilinxPlacedOverlay(name, designInput, shellInput)
{
  shell { InModuleBody {
    val pin_locations = Map(
      "PMOD_J52" -> Seq("AW15",      "AU16",      "AV16",      "AY14",      "AY15"),
      "PMOD_J53" -> Seq( "N30",       "L31",       "P29",       "N28",       "M30"),
      "FMC_J2"   -> Seq("AL12",      "AN15",      "AP15",      "AM12",      "AK12"))
    val pins      = Seq(io.jtag_TCK, io.jtag_TMS, io.jtag_TDI, io.jtag_TDO, io.srst_n)

    shell.sdc.addClock("JTCK", IOPin(io.jtag_TCK), 10)
    shell.sdc.addGroup(clocks = Seq("JTCK"))
    shell.xdc.clockDedicatedRouteFalse(IOPin(io.jtag_TCK))

    val pin_voltage:String = if(shellInput.location.get == "PMOD_J53") "LVCMOS12" else "LVCMOS18"

    (pin_locations(shellInput.location.get) zip pins) foreach { case (pin_location, ioport) =>
      val io = IOPin(ioport)
      shell.xdc.addPackagePin(io, pin_location)
      shell.xdc.addIOStandard(io, pin_voltage)
      shell.xdc.addPullup(io)
      shell.xdc.addIOB(io)
    }
  } }
}
class JTAGDebugGENESYS2ShellPlacer(shell: GENESYS2ShellBasicOverlays, val shellInput: JTAGDebugShellInput)(implicit val valName: ValName)
  extends JTAGDebugShellPlacer[GENESYS2ShellBasicOverlays] {
  def place(designInput: JTAGDebugDesignInput) = new JTAGDebugGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

// cjtag

class cJTAGDebugGENESYS2PlacedOverlay(val shell: GENESYS2ShellBasicOverlays, name: String, val designInput: cJTAGDebugDesignInput, val shellInput: cJTAGDebugShellInput)
  extends cJTAGDebugXilinxPlacedOverlay(name, designInput, shellInput)
{
  shell { InModuleBody {
    shell.sdc.addClock("JTCKC", IOPin(io.cjtag_TCKC), 10)
    shell.sdc.addGroup(clocks = Seq("JTCKC"))
    shell.xdc.clockDedicatedRouteFalse(IOPin(io.cjtag_TCKC))
    val packagePinsWithPackageIOs = Seq(("AW11", IOPin(io.cjtag_TCKC)),
                                        ("AP13", IOPin(io.cjtag_TMSC)),
                                        ("AY10", IOPin(io.srst_n)))

    packagePinsWithPackageIOs foreach { case (pin, io) => {
      shell.xdc.addPackagePin(io, pin)
      shell.xdc.addIOStandard(io, "LVCMOS18")
    } }
      shell.xdc.addPullup(IOPin(io.cjtag_TCKC))
      shell.xdc.addPullup(IOPin(io.srst_n))
  } }
}
class cJTAGDebugGENESYS2ShellPlacer(shell: GENESYS2ShellBasicOverlays, val shellInput: cJTAGDebugShellInput)(implicit val valName: ValName)
  extends cJTAGDebugShellPlacer[GENESYS2ShellBasicOverlays] {
  def place(designInput: cJTAGDebugDesignInput) = new cJTAGDebugGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

class JTAGDebugBScanGENESYS2PlacedOverlay(val shell: GENESYS2ShellBasicOverlays, name: String, val designInput: JTAGDebugBScanDesignInput, val shellInput: JTAGDebugBScanShellInput)
  extends JTAGDebugBScanXilinxPlacedOverlay(name, designInput, shellInput)
class JTAGDebugBScanGENESYS2ShellPlacer(val shell: GENESYS2ShellBasicOverlays, val shellInput: JTAGDebugBScanShellInput)(implicit val valName: ValName)
  extends JTAGDebugBScanShellPlacer[GENESYS2ShellBasicOverlays] {
  def place(designInput: JTAGDebugBScanDesignInput) = new JTAGDebugBScanGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

// ----------- UART ------------
class UARTGENESYS2PlacedOverlay(val shell: GENESYS2ShellBasicOverlays, name: String, val designInput: UARTDesignInput, val shellInput: UARTShellInput)
  extends UARTXilinxPlacedOverlay(name, designInput, shellInput, true)
{
    // set xdc constraints for UART TXD and RXD
    shell { InModuleBody {
    val uartLocations = List(List("Y23", "Y20"))
    val packagePinsWithPackageIOs = Seq((uartLocations(shellInput.index)(0), IOPin(io.txd)),
                                        (uartLocations(shellInput.index)(1), IOPin(io.rxd)))

    packagePinsWithPackageIOs foreach { case (pin, io) => {
      shell.xdc.addPackagePin(io, pin)
      shell.xdc.addIOStandard(io, "LVCMOS33")
      shell.xdc.addIOB(io)
    } }
  } }
}

class UARTGENESYS2ShellPlacer(val shell: GENESYS2ShellBasicOverlays, val shellInput: UARTShellInput)(implicit val valName: ValName)
  extends UARTShellPlacer[GENESYS2ShellBasicOverlays]
{
  def place(designInput: UARTDesignInput) = new UARTGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

class GENESYS2Shell()(implicit p: Parameters) extends GENESYS2ShellBasicOverlays
{
  val pmod_is_sdio  = p(GENESYS2ShellPMOD) == "SDIO"
  val pmod_j53_is_jtag = p(GENESYS2ShellPMOD2) == "PMODJ53_JTAG"
  val jtag_location = Some(if (pmod_is_sdio) (if (pmod_j53_is_jtag) "PMOD_J53" else "FMC_J2") else "PMOD_J52")

  // Order matters; ddr depends on sys_clock
  val uart      = Overlay(UARTOverlayKey, new UARTGENESYS2ShellPlacer(this, UARTShellInput()))
  val sdio      = if (pmod_is_sdio) Some(Overlay(SPIOverlayKey, new SDIOGENESYS2ShellPlacer(this, SPIShellInput()))) else None
  val jtag      = Overlay(JTAGDebugOverlayKey, new JTAGDebugGENESYS2ShellPlacer(this, JTAGDebugShellInput(location = jtag_location)))
  //val cjtag     = Overlay(cJTAGDebugOverlayKey, new cJTAGDebugGENESYS2ShellPlacer(this, cJTAGDebugShellInput()))
  //val jtagBScan = Overlay(JTAGDebugBScanOverlayKey, new JTAGDebugBScanGENESYS2ShellPlacer(this, JTAGDebugBScanShellInput()))
  //val fmc       = Overlay(PCIeOverlayKey, new PCIeGENESYS2FMCShellPlacer(this, PCIeShellInput()))
  //val edge      = Overlay(PCIeOverlayKey, new PCIeGENESYS2EdgeShellPlacer(this, PCIeShellInput()))

  val topDesign = LazyModule(p(DesignKey)(designParameters))

  // Place the sys_clock at the Shell if the user didn't ask for it
  designParameters(ClockInputOverlayKey).foreach { unused =>
    val source = unused.place(ClockInputDesignInput()).overlayOutput.node
    val sink = ClockSinkNode(Seq(ClockSinkParameters()))
    sink := source
  }

  override lazy val module = new LazyRawModuleImp(this) {
    val reset = IO(Input(Bool())) // CPU reset
    xdc.addPackagePin(reset, "R19") 
    xdc.addIOStandard(reset, "LVCMOS33")

    val reset_ibuf = Module(new IBUF)
    reset_ibuf.io.I := reset

    val sysclk: Clock = sys_clock.get() match {
      case Some(x: SysClockGENESYS2PlacedOverlay) => x.clock
    }

    val powerOnReset: Bool = PowerOnResetFPGAOnly(sysclk)
    sdc.addAsyncPath(Seq(powerOnReset))

    // 有chiplink
    val ereset: Bool = chiplink.get() match {
      case Some(x: ChipLinkGENESYS2PlacedOverlay) => !x.ereset_n
      case _ => false.B
    }
    pllReset := (reset_ibuf.io.O || powerOnReset || ereset)
    
    // 沒有chiplink
    // pllReset := (reset_ibuf.io.O || powerOnReset )
  }
}


