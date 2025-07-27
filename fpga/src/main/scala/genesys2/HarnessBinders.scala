package chipyard.fpga.genesys2

import chisel3._
import chisel3.experimental.{BaseModule}

import org.chipsalliance.diplomacy.nodes.{HeterogeneousBag}
import freechips.rocketchip.tilelink.{TLBundle}

import sifive.blocks.devices.uart.{UARTPortIO}
import sifive.blocks.devices.spi.{HasPeripherySPI, SPIPortIO}

import chipyard._
import chipyard.harness._
import chipyard.iobinders._
// GENESYS2FPGATestHarness = gensys2Outer
/*** UART ***/
class WithGENESYS2UARTHarnessBinder extends HarnessBinder({
  case (th: GENESYS2FPGATestHarnessImp, port: UARTPort, chipId: Int) => {
    th.genesys2Outer.io_uart_bb.bundle <> port.io
  }
})

/*** SPI ***/

class WithGENESYS2SPISDCardHarnessBinder extends HarnessBinder({
  case (th: GENESYS2FPGATestHarnessImp, port: SPIPort, chipId: Int) => {
    th.genesys2Outer.io_spi_bb.bundle <> port.io
  }
    
  })

/*** Experimental DDR ***/

class WithGENESYS2DDRMemHarnessBinder extends HarnessBinder({
  case (th: GENESYS2FPGATestHarnessImp, port: TLMemPort, chipId: Int) => {
    val bundles = th.genesys2Outer.ddrClient.out.map(_._1)
    val ddrClientBundle = Wire(new HeterogeneousBag(bundles.map(_.cloneType)))
    bundles.zip(ddrClientBundle).foreach { case (bundle, io) => bundle <> io }
    ddrClientBundle <> port.io
  }
})

class WithGENESYS2JTAGHarnessBinder extends HarnessBinder({
  case (th: GENESYS2FPGATestHarnessImp, port: JTAGPort, chipId: Int) => {
    val jtag_io = th.genesys2Outer.jtagPlacedOverlay.overlayOutput.jtag.getWrappedValue
    port.io.TCK := jtag_io.TCK
    port.io.TMS := jtag_io.TMS
    port.io.TDI := jtag_io.TDI
    jtag_io.TDO.data := port.io.TDO
    jtag_io.TDO.driven := true.B
    // ignore srst_n
    jtag_io.srst_n := DontCare

  }
})
