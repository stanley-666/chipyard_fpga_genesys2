# Chipyard FPGA Prototype Support for Digilent Genesys2

This repository provides FPGA prototype support for the Digilent Genesys2 FPGA board based on **Chipyard v1.13.0**.

The upstream Chipyard FPGA flow does not officially provide a Genesys2 target. This project adds a Genesys2 FPGA shell and board integration flow by adapting the Chipyard FPGA prototype infrastructure and SiFive-style overlay structure.

Currently supported peripherals:

- UART through SiFive UART
- JTAG
- MicroSD card boot through SiFive SPI-to-MMC
- Zero-stage bootloader stored in the FPGA boot ROM

This repository is intended as a board-integration reference for running Chipyard-generated RISC-V SoCs on the Genesys2 FPGA platform.

---

## Upstream Project

This project is based on:

- Chipyard v1.13.0  
  <https://github.com/ucb-bar/chipyard>
- fpga-shells  
  <https://github.com/sifive/fpga-shells>

Before using this repository, please follow the official Chipyard documentation and complete the FPGA setup flow first.

---
## Setup Overview

First, set up Chipyard and its FPGA environment by following the official Chipyard documentation.

After Chipyard v1.13.0 is fully initialized, copy or patch the Genesys2-specific files from this repository into the corresponding Chipyard directories.

Typical modified locations include:

```text
build.sbt
fpga/
fpga-shells/
```
---
## Build Bitstream with Zero-Stage Bootloader
After the Genesys2 files are placed under fpga/ and fpga-shells/, the following script can be used as a build helper.

The script performs the following steps:

Builds the zero-stage SD bootloader.
Generates the FPGA bitstream.
Copies the generated bitstream.
Exports the generated DTS file.
Compiles DTS to DTB if dtc is available.

You may modify the paths, configuration name, and peripheral bus clock according to your local Chipyard setup.
```bash!
#!/home/stanley/chipyard_1.13.0/chipyard/.conda-env/bin/bash
set -e

chipyard_root="$HOME/chipyard_1.13.0/chipyard"
CONFIG="Rocket90MHZ"
PBUS_CLK=90000000

generated_dir="$chipyard_root/fpga/generated-src/chipyard.fpga.genesys2.GENESYS2FPGATestHarness.$CONFIG"
dest_dir="$chipyard_root/fpga/bitstream_copy"
dts_dir="$chipyard_root/fpga/dts_copy"
echo "Script to build bootrom of zero stage bootloader for Rocket Chip on the Genesys2 FPGA board"

cd $chipyard_root
source env.sh

# build sdboot
cd $chipyard_root/fpga/src/main/resources/genesys2/sdboot
make clean
make PBUS_CLK=$PBUS_CLK

# build bitstream
cd $chipyard_root/fpga
make clean
make CONFIG=$CONFIG bitstream -j16

# copy bitstream, dts
mkdir -p $dest_dir
mkdir -p $dts_dir
cp $generated_dir/obj/*.bit $dest_dir/$CONFIG.bit
cp $generated_dir/*.dts $dts_dir/$CONFIGz.dts

# compile dts -> dtb
if command -v dtc >/dev/null 2>&1; then
    dtc -I dts -O dtb -o $dts_dir/$CONFIG.dtb $dts_dir/$CONFIG.dts
    echo "DTB compiled: $dts_dir/$CONFIG.dtb"
else
    echo "WARNING: dtc not found in PATH, cannot compile DTS to DTB."
fi

echo "==================================================="
echo "Done building bitstream."
echo "Bitstream copied to: $dest_dir/$CONFIG.bit"
echo "DTS/DTB saved to: $dts_dir/$CONFIG.dts / $dts_dir/$CONFIG.dtb"
echo "Now program the FPGA with:"
echo "  Please open hardware manager to program $dest_dir/$CONFIG.bit"
echo "==================================================="

```
# Attribution and Chipyard-related Publications
This project is derived from and designed to work with the Chipyard / SiFive FPGA shell ecosystem. Original copyrights and licenses remain with their respective upstream authors.
If you use this repository, the Genesys2 FPGA shell modifications, the board integration flow, or the build scripts in academic work, please cite the related Chipyard publication and this repository.
```
@article{chipyard,
  author={Amid, Alon and Biancolin, David and Gonzalez, Abraham and Grubb, Daniel and Karandikar, Sagar and Liew, Harrison and Magyar,   Albert and Mao, Howard and Ou, Albert and Pemberton, Nathan and Rigge, Paul and Schmidt, Colin and Wright, John and Zhao, Jerry and Shao, Yakun Sophia and Asanovi\'{c}, Krste and Nikoli\'{c}, Borivoje},
  journal={IEEE Micro},
  title={Chipyard: Integrated Design, Simulation, and Implementation Framework for Custom SoCs},
  year={2020},
  volume={40},
  number={4},
  pages={10-21},
  doi={10.1109/MM.2020.2996616},
  ISSN={1937-4143},
}
```
# License

Unless otherwise noted, the original code and modifications in this repository are released under the license specified in the LICENSE file.

Some files are adapted from the Chipyard / SiFive FPGA shell ecosystem. Those upstream components retain their original copyright and license terms. This repository preserves upstream attribution where applicable.

