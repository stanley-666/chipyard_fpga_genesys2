# Chipyard FPGA Prototype support for genesys2
https://github.com/ucb-bar/chipyard
version: chipyard v1.13.0
* first setup chipyard fpga path (see the instruction from chipyard doc first)
* second overwrite build.sbt, fpga directory

# Attribution and Chipyard-related Publications

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

## Intro

Because chipyard official did not support genesys2 FPGA board for fpga prototype.
This project integrated genesys2 fpga into chipyard FPGA flow by using the overlay from SiFive.

Right now this project only support uart (Sifive uart), jtag, microsdcard (with SiFive spi to mmc).

After you finished "build-setup" chipyard v1.13.0 and copy genesys2 file under fpga/ , fpga-shells/

you can modify this bash file and put it under fpga/
and you can get the bitstream file that contains sdboot.bin (zero stage bootloader) in rom.
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

and if you want to build and run riscv system with Linux, please go to my u-boot, opensbi repos for more details!
