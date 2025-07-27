package chipyard.fpga.genesys2

import sys.process._

import org.chipsalliance.cde.config.{Config, Parameters}
import freechips.rocketchip.subsystem.{SystemBusKey, PeripheryBusKey, ControlBusKey, ExtMem}
import freechips.rocketchip.devices.debug.{DebugModuleKey, ExportDebug, JTAG}
import freechips.rocketchip.devices.tilelink.{DevNullParams, BootROMLocated}
import freechips.rocketchip.diplomacy.{RegionType, AddressSet}
import freechips.rocketchip.resources.{DTSModel, DTSTimebase}

import sifive.blocks.devices.spi.{PeripherySPIKey, SPIParams}
import sifive.blocks.devices.uart.{PeripheryUARTKey, UARTParams}
import sifive.blocks.devices.gpio.{PeripheryGPIOKey, GPIOParams}

import sifive.fpgashells.shell.{DesignKey}
import sifive.fpgashells.shell.xilinx.{GENESYS2ShellPMOD, GENESYS2DDRSize}

import testchipip.serdes.{SerialTLKey}

import chipyard._
import chipyard.harness._


// synthesizable config for GENESYS2

class WithDefaultPeripherals extends Config((site, here, up) => {
  case PeripheryUARTKey => List(UARTParams(address = BigInt(0x64000000L)))
  case PeripherySPIKey => List(SPIParams(rAddress = BigInt(0x64001000L)))
  //case PeripheryGPIOKey => List(GPIOParams(address = BigInt(0x64002000L)))
  case GENESYS2ShellPMOD => "SDIO"
})

class WithSystemModifications extends Config((site, here, up) => {
  //case PeripheryBusKey => up(PeripheryBusKey, site).copy(dtsFrequency = Some(site(FPGAFrequencyKey).toInt*1000000))
  case DTSTimebase => BigInt((1e6).toLong)
  case BootROMLocated(x) => up(BootROMLocated(x), site).map { p =>
    // invoke makefile for sdboot
    val freqMHz = (site(SystemBusKey).dtsFrequency.get / (1000 * 1000)).toLong
    val make = s"make -C fpga/src/main/resources/genesys2/sdboot PBUS_CLK=${freqMHz} bin"
    require (make.! == 0, "Failed to build bootrom")
    p.copy(hang = 0x10000, contentFileName = s"./fpga/src/main/resources/genesys2/sdboot/build/sdboot.bin")
  }
  case ExtMem => up(ExtMem, site).map(x => x.copy(master = x.master.copy(size = site(GENESYS2DDRSize)))) // set extmem to DDR size
  case SerialTLKey => Nil // remove serialized tl port
})

// DOC include start: AbstractGENESYS2 and Rocket
class WithGENESYS2Tweaks extends Config(
  // clocking
  new chipyard.harness.WithAllClocksFromHarnessClockInstantiator ++
  new chipyard.clocking.WithPassthroughClockGenerator ++
  new chipyard.config.WithUniformBusFrequencies(100) ++
  new WithFPGAFrequency(100) ++ // default 100MHz freq
  // harness binders
  new WithGENESYS2UARTHarnessBinder ++
  new WithGENESYS2SPISDCardHarnessBinder ++
  new WithGENESYS2DDRMemHarnessBinder ++
  new WithGENESYS2JTAGHarnessBinder ++
  // other configuration
  new WithDefaultPeripherals ++
  new chipyard.config.WithTLBackingMemory ++ // use TL backing memory
  new WithSystemModifications ++ // setup busses, use sdboot bootrom, setup ext. mem. size
  new chipyard.config.WithNoDebug ++ // remove debug module
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new freechips.rocketchip.subsystem.WithNMemoryChannels(1))


class LargeNVDLARocketGENESYS2Config extends Config(
  new nvidia.blocks.dla.WithNVDLA("large", true) ++  
  new WithGENESYS2Tweaks ++
  new chipyard.RocketConfig
)

class SmallNVDLARocketGENESYS2Config extends Config(
  new nvidia.blocks.dla.WithNVDLA("small") ++ 
  new WithGENESYS2Tweaks ++
  new chipyard.RocketConfig
)

class FFTRocketGENESYS2Config extends Config(
  new fftgenerator.WithFFTGenerator(numPoints=8, width=16, decPt=8) ++ // add FFT generator
  new WithGENESYS2Tweaks ++
  new chipyard.RocketConfig
)

class RocketGENESYS2Config extends Config(
  new WithFPGAFrequency(50) ++
  new WithGENESYS2Tweaks ++
  new chipyard.RocketConfig
)
// DOC include end: AbstractGENESYS2 and Rocket

class BoomGENESYS2Config extends Config(
  new WithFPGAFrequency(50) ++
  new WithGENESYS2Tweaks ++
  new chipyard.MegaBoomV3Config)

class WithFPGAFrequency(fMHz: Double) extends Config(
  new chipyard.harness.WithHarnessBinderClockFreqMHz(fMHz) ++
  new chipyard.config.WithSystemBusFrequency(fMHz) ++
  new chipyard.config.WithPeripheryBusFrequency(fMHz) ++
  new chipyard.config.WithControlBusFrequency(fMHz) ++
  new chipyard.config.WithFrontBusFrequency(fMHz) ++
  new chipyard.config.WithMemoryBusFrequency(fMHz)
)

// test bugs config

class TESTSDGENESYS2Config extends Config(
  new WithFPGAFrequency(50) ++
  new WithGENESYS2Tweaks ++
  new chipyard.RocketConfig
)

// for spike or verilator simulation config

class SimRocketGENESYS2Config extends Config(
  new freechips.rocketchip.subsystem.WithExtMemSize((1<<30) * 1L) ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig
)