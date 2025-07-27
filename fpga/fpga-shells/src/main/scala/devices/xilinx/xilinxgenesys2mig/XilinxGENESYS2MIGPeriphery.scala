// See LICENSE for license details.
package sifive.fpgashells.devices.xilinx.xilinxgenesys2mig

import freechips.rocketchip.diplomacy.{AddressRange, LazyModule, LazyModuleImp}
import freechips.rocketchip.subsystem.{BaseSubsystem, MBUS}
import freechips.rocketchip.tilelink.TLWidthWidget
import org.chipsalliance.cde.config._

case object MemoryXilinxDDRKey extends Field[XilinxGENESYS2MIGParams]

trait HasMemoryXilinxGENESYS2MIG { this: BaseSubsystem =>
  val module: HasMemoryXilinxGENESYS2MIGModuleImp

  val xilinxgenesys2mig = LazyModule(new XilinxGENESYS2MIG(p(MemoryXilinxDDRKey)))

  private val mbus = locateTLBusWrapper(MBUS)
  mbus.coupleTo("xilinxgenesys2mig") { xilinxgenesys2mig.node := TLWidthWidget(mbus.beatBytes) := _ }
}

trait HasMemoryXilinxGENESYS2MIGBundle {
  val xilinxgenesys2mig: XilinxGENESYS2MIGIO
  def connectXilinxGENESYS2MIGToPads(pads: XilinxGENESYS2MIGPads) {
    pads <> xilinxgenesys2mig
  }
}

trait HasMemoryXilinxGENESYS2MIGModuleImp extends LazyModuleImp with HasMemoryXilinxGENESYS2MIGBundle {
  val outer: HasMemoryXilinxGENESYS2MIG
  val ranges = AddressRange.fromSets(p(MemoryXilinxDDRKey).address)
  require (ranges.size == 1, "DDR range must be contiguous")
  val depth = ranges.head.size
  val xilinxgenesys2mig = IO(new XilinxGENESYS2MIGIO(depth))

  xilinxgenesys2mig <> outer.xilinxgenesys2mig.module.io.port
}
