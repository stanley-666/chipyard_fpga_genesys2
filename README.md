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

