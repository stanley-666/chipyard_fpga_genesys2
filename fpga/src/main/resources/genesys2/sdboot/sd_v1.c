// See LICENSE.Sifive for license details.
#include <stdint.h>
#include <platform.h>
#include "common.h"
#include "uart.h"
#define DEBUG
#include "kprintf.h"

#define MAX_CORES 8

// A sector is 512 bytes, so ((1 << 11) * 512) = 1 MiB
#define PAYLOAD_SIZE	(16 << 11)

// The sector at which the BBL partition starts
#define BBL_PARTITION_START_SECTOR 34

#ifndef TL_CLK
#error Must define TL_CLK
#endif

#define F_CLK TL_CLK

// #ifndef BOOTRAM_MEM_ADDR

static volatile uint32_t * const spi = (void *)(SPI_CTRL_ADDR);

static inline uint8_t spi_xfer(uint8_t d)
{
	int32_t r;

	REG32(spi, SPI_REG_TXFIFO) = d;
	do {
		r = REG32(spi, SPI_REG_RXFIFO);
	} while (r < 0);
	return r;
}

static inline uint8_t sd_dummy(void)
{
	return spi_xfer(0xFF);
}

static uint8_t sd_cmd(uint8_t cmd, uint32_t arg, uint8_t crc)
{
	unsigned long n;
	uint8_t r;

	REG32(spi, SPI_REG_CSMODE) = SPI_CSMODE_HOLD;
	sd_dummy();
	spi_xfer(cmd);
	spi_xfer(arg >> 24);
	spi_xfer(arg >> 16);
	spi_xfer(arg >> 8);
	spi_xfer(arg);
	spi_xfer(crc);

	n = 1000;
	do {
		r = sd_dummy();
		if (!(r & 0x80)) {
//			dprintf("sd:cmd: %hx\r\n", r);
			goto done;
		}
	} while (--n > 0);
	kputln("sd_cmd: timeout");
done:
	return r;
}

static inline void sd_cmd_end(void)
{
	sd_dummy();
	REG32(spi, SPI_REG_CSMODE) = SPI_CSMODE_AUTO;
}


static void sd_poweron(void)
{
	long i;
	REG32(spi, SPI_REG_SCKDIV) = (F_CLK / 300000UL);
	REG32(spi, SPI_REG_CSMODE) = SPI_CSMODE_OFF;
	for (i = 10; i > 0; i--) {
		sd_dummy();
	}
	REG32(spi, SPI_REG_CSMODE) = SPI_CSMODE_AUTO;
}

static int sd_cmd0(void)
{
	int rc;
	dputs("CMD0");
	rc = (sd_cmd(0x40, 0, 0x95) != 0x01);
	sd_cmd_end();
	return rc;
}

static int sd_cmd8(void)
{
	int rc;
	dputs("CMD8");
	rc = (sd_cmd(0x48, 0x000001AA, 0x87) != 0x01);
	sd_dummy(); /* command version; reserved */
	sd_dummy(); /* reserved */
	rc |= ((sd_dummy() & 0xF) != 0x1); /* voltage */
	rc |= (sd_dummy() != 0xAA); /* check pattern */
	sd_cmd_end();
	return rc;
}

static void sd_cmd55(void)
{
	sd_cmd(0x77, 0, 0x65);
	sd_cmd_end();
}

static int sd_acmd41(void)
{
	uint8_t r;
	int timeout = 10000;
	dputs("ACMD41 ");

	do {
		sd_cmd55();
		r = sd_cmd(0x69, 0x40000000, 0x77); /* HCS = 1 */
		kputc('.'); // 印出 progress
	} while (r == 0x01 && --timeout > 0);

	if (timeout == 0) {
		kputln(" TIMEOUT");
		return 1;
	}
	kputln(" OK");
	return (r != 0x00);
}

static int sd_cmd58(void)
{
	int rc;
	dputs("CMD58");
	rc = (sd_cmd(0x7A, 0, 0xFD) != 0x00);
	rc |= ((sd_dummy() & 0x80) != 0x80); /* Power up status */
	sd_dummy();
	sd_dummy();
	sd_dummy();
	sd_cmd_end();
	return rc;
}

static int sd_cmd16(void)
{
	int rc;
	dputs("CMD16");
	rc = (sd_cmd(0x50, 0x200, 0x15) != 0x00);
	sd_cmd_end();
	return rc;
}

static uint16_t crc16_round(uint16_t crc, uint8_t data) {
	crc = (uint8_t)(crc >> 8) | (crc << 8);
	crc ^= data;
	crc ^= (uint8_t)(crc >> 4) & 0xf;
	crc ^= crc << 12;
	crc ^= (crc & 0xff) << 5;
	return crc;
}

#define SPIN_SHIFT	6
#define SPIN_UPDATE(i)	(!((i) & ((1 << SPIN_SHIFT)-1)))
#define SPIN_INDEX(i)	(((i) >> SPIN_SHIFT) & 0x3)

static const char spinner[] = { '-', '/', '|', '\\' };

static int copy(void)
{
	volatile uint8_t *p = (void *)(PAYLOAD_DEST);
	long i = PAYLOAD_SIZE;
	int rc = 0;

	dputs("CMD18");
	kprintf("LOADING  ");

    // John: Let's go slow until we get this working
	//REG32(spi, SPI_REG_SCKDIV) = (F_CLK / 16666666UL);
	REG32(spi, SPI_REG_SCKDIV) = (F_CLK / 5000000UL);
	if (sd_cmd(0x52, BBL_PARTITION_START_SECTOR, 0xE1) != 0x00) {
		sd_cmd_end();
		return 1;
	}
	do {
		uint16_t crc, crc_exp;
		long n;

		crc = 0;
		n = 512;
		while (sd_dummy() != 0xFE);
		do {
			uint8_t x = sd_dummy();
			*p++ = x;
			crc = crc16_round(crc, x);
		} while (--n > 0);

		crc_exp = ((uint16_t)sd_dummy() << 8);
		crc_exp |= sd_dummy();

		if (crc != crc_exp) {
			kputln("\b- CRC mismatch ");
			rc = 1;
			break;
		}

		if (SPIN_UPDATE(i)) {
			kputln('\b');
			kputln(spinner[SPIN_INDEX(i)]);
		}
	} while (--i > 0);
	sd_cmd_end();
	sd_cmd(0x4C, 0, 0x01);
	sd_cmd_end();
	kputln("\b ");
	return rc;
}

static void print_dec(uint32_t num)
{
    char buf[11]; // 10 digits max for 32-bit + null terminator
    int i = 10;
    buf[i] = '\0';

    if (num == 0) {
        kputc('0');
        return;
    }

    while (num > 0 && i > 0) {
        buf[--i] = '0' + (num % 10);
        num /= 10;
    }

    kputln(&buf[i]);
}


int main(void)
{
	uart_init();
	kputln("test kputln SD BOOT");	
	sd_poweron();
	uint32_t sckdiv = REG32(spi, SPI_REG_SCKDIV);
	uint32_t spi_clk = F_CLK / (sckdiv ? sckdiv : 1); // 防止除 0
	kputln("SPI Clock:");
	print_dec(spi_clk);
	kputln(" Hz");

	for (volatile int i = 0; i < 100000; i++); // delay
	if (sd_cmd0() ||
	    sd_cmd8() ||
	    sd_acmd41() ||
	    sd_cmd58() ||
	    sd_cmd16() ||
	    copy()) {
		kputln("ERROR");
		return 1;
	}	

	kputln("BOOT");
	__asm__ __volatile__ ("fence.i" : : : "memory");

	return 0;
}