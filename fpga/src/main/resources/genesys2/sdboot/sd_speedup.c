// See LICENSE.Sifive for license details.
#include <stdint.h>
#include <stddef.h>  
#include <platform.h>
#include "common.h"
#include "uart.h"
#define DEBUG
#include "kprintf.h"

#define MAX_CORES 8

// A sector is 512 bytes, so ((1 << 11) * 512) = 1 MiB
#define PAYLOAD_SIZE_B	(3*1024*1024)
// A sector is 512 bytes, so (1 << 11) * 512B = 1 MiB
#define SECTOR_SIZE_B 512
// Payload size in # of sectors
#define PAYLOAD_SIZE (PAYLOAD_SIZE_B / SECTOR_SIZE_B)
// The sector at which the BBL partition starts
#define BBL_PARTITION_START_SECTOR 34

#ifndef TL_CLK
#error Must define TL_CLK
#endif

#define F_CLK (TL_CLK)
#define SD_INIT_SCKDIV (F_CLK / 300000UL)
#define SD_LOAD_SCKDIV 1UL
#define SPI_SCK_HZ(div) (F_CLK / (2UL * ((div) + 1UL)))

// The SPI clock is F_CLK / (2 * (SCKDIV + 1)).


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

// Optimize bulk SPI transfers to reduce register access overhead.
static inline void spi_read_bulk(uint8_t *buffer, int count)
{
	volatile uint32_t *txfifo = (volatile uint32_t *)((char*)spi + SPI_REG_TXFIFO);
	volatile uint32_t *rxfifo = (volatile uint32_t *)((char*)spi + SPI_REG_RXFIFO);
	int i;
	
	// Pre-fill the TX FIFO.
	for (i = 0; i < count && i < 8; i++) {  // Assume a FIFO depth of 8.
		*txfifo = 0xFF;
	}
	
	// Read the received data.
	for (i = 0; i < count; i++) {
		int32_t r;
		
		// Keep filling the TX FIFO while more bytes remain.
		if (i + 8 < count) {
			*txfifo = 0xFF;
		}
		
		// Wait for and read the next received byte.
		do {
			r = *rxfifo;
		} while (r < 0);
		
		buffer[i] = (uint8_t)r;
	}
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
	REG32(spi, SPI_REG_SCKDIV) = SD_INIT_SCKDIV;
	REG32(spi, SPI_REG_CSMODE) = SPI_CSMODE_OFF;
	for (i = 10; i > 0; i--) {
		sd_dummy();
	}
	REG32(spi, SPI_REG_CSMODE) = SPI_CSMODE_AUTO;
}

static int sd_cmd0(void)
{
	int rc;
	dputs("CMD0: ");
	rc = (sd_cmd(0x40, 0, 0x95) != 0x01);
	sd_cmd_end();
	kputln(rc ? "FAILED" : "OK");
	return rc;
}

static int sd_cmd8(void)
{
	int rc;
	dputs("CMD8: ");
	rc = (sd_cmd(0x48, 0x000001AA, 0x87) != 0x01);
	sd_dummy(); /* command version; reserved */
	sd_dummy(); /* reserved */
	rc |= ((sd_dummy() & 0xF) != 0x1); /* voltage */
	rc |= (sd_dummy() != 0xAA); /* check pattern */
	sd_cmd_end();
	kputln(rc ? "FAILED" : "OK");
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
	dputs("ACMD41: ");
	do {
		sd_cmd55();
		r = sd_cmd(0x69, 0x40000000, 0x77); /* HCS = 1 */
	} while (r == 0x01);
	r = (r != 0x00);
	kputln(r ? "FAILED" : "OK");
	return r;
}

static int sd_cmd58(void)
{
	int rc;
	dputs("CMD58: ");
	rc = (sd_cmd(0x7A, 0, 0xFD) != 0x00);
	rc |= ((sd_dummy() & 0x80) != 0x80); /* Power up status */
	sd_dummy();
	sd_dummy();
	sd_dummy();
	sd_cmd_end();
	kputln(rc ? "FAILED" : "OK");
	return rc;
}

static int sd_cmd16(void)
{
	int rc;
	dputs("CMD16: ");
	rc = (sd_cmd(0x50, 0x200, 0x15) != 0x00);
	sd_cmd_end();
	kputln(rc ? "FAILED" : "OK");
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
#define SPIN_INDEX(i)	(((i) >> SPIN_SHIFT) & 0x3)

static const char spinner[] = { '-', '/', '|', '\\' };

static int copy(void)
{
	volatile uint8_t *p = (void *)(PAYLOAD_DEST);
	long i = PAYLOAD_SIZE;
	int rc = 0;

	REG32(spi, SPI_REG_SCKDIV) = SD_LOAD_SCKDIV;
	kprintf("Load clock: %d Hz\r\n", (int)SPI_SCK_HZ(SD_LOAD_SCKDIV));
	kprintf("Loading %d bytes from sector %d to 0x%lx\r\n",
		PAYLOAD_SIZE_B, BBL_PARTITION_START_SECTOR, (unsigned long)PAYLOAD_DEST);
	dputs("CMD18: ");

	if (sd_cmd(0x52, BBL_PARTITION_START_SECTOR, 0xE1) != 0x00) {
		sd_cmd_end();
		kputln("FAILED");
		return 1;
	}
	kputln("OK");
	
	do {
		uint16_t crc, crc_exp;

		crc = 0;
		while (sd_dummy() != 0xFE);

		// Read an entire sector in bulk.
		uint8_t sector_buf[SECTOR_SIZE_B];
		spi_read_bulk(sector_buf, SECTOR_SIZE_B);
		
		// Calculate the CRC and copy the data to the destination.
		for (int j = 0; j < SECTOR_SIZE_B; j++) {
			uint8_t x = sector_buf[j];
			*p++ = x;
			crc = crc16_round(crc, x);
		}

		crc_exp = ((uint16_t)sd_dummy() << 8);
		crc_exp |= sd_dummy();

		if (crc != crc_exp) {
			kprintf("\bCRC mismatch at sector %d\r\n",
				BBL_PARTITION_START_SECTOR + (PAYLOAD_SIZE - i));
			rc = 1;
			break;
		}

		// Reduce the spinner update frequency.
		// Update once every 64 sectors to reduce I/O overhead.
		if ((i & 63) == 0) {
			kputc('\b');
			kputc(spinner[SPIN_INDEX(i >> 6)]);
		}
	} while (--i > 0);
	
	sd_cmd_end();

	sd_cmd(0x4C, 0, 0x01);
	sd_cmd_end();
	kputs("\b ");

	kprintf(rc ? "\r\nLoad failed\r\n" : "\r\nLoad complete\r\n");

	return rc;
}

int main(void)
{
	uart_init();
	kputln("SD Boot v1.0");	
	sd_poweron();
	kprintf("TL clock: %d Hz\r\n", (int)F_CLK);
	kprintf("SD init clock: %d Hz\r\n", (int)SPI_SCK_HZ(SD_INIT_SCKDIV));

	if (sd_cmd0() ||
	    sd_cmd8() ||
	    sd_acmd41() ||
	    sd_cmd58() ||
	    sd_cmd16() ||
	    copy()) {
		kputln("SD boot failed");
		return 1;
	}	

	kprintf("Jumping to OS at 0x%x\r\n", (unsigned long)PAYLOAD_DEST);
	// Ensure subsequent instruction fetches observe the loaded image.
    __asm__ __volatile__ ("fence.i" : : : "memory");

	return 0;
}
