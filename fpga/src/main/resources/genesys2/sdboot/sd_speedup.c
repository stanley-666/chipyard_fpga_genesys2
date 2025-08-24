// See LICENSE.Sifive for license details.
#include <stdint.h>
#include <stddef.h>  
#include <platform.h>
#include "common.h"
#include "uart.h"
#define DEBUG
#include "kprintf.h"

// SD加速優化選項
#define SD_SPEED_OPTIMIZE 1  // 設為0以使用原來的保守設定

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

// SPI SCLK frequency, in kHz
// We are using the 25MHz High Speed mode. If this speed is not supported by the
// SD card, consider changing to the Default Speed mode (12.5 MHz).

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

// 批量SPI傳輸優化 - 減少寄存器存取開銷
static inline void spi_read_bulk(uint8_t *buffer, int count)
{
	volatile uint32_t *txfifo = (volatile uint32_t *)((char*)spi + SPI_REG_TXFIFO);
	volatile uint32_t *rxfifo = (volatile uint32_t *)((char*)spi + SPI_REG_RXFIFO);
	int i;
	
	// 預先填充TX FIFO
	for (i = 0; i < count && i < 8; i++) {  // 假設FIFO深度為8
		*txfifo = 0xFF;
	}
	
	// 讀取數據
	for (i = 0; i < count; i++) {
		int32_t r;
		
		// 如果還有更多數據要發送，繼續填充TX FIFO
		if (i + 8 < count) {
			*txfifo = 0xFF;
		}
		
		// 等待並讀取數據
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
	// 使用原來的計算方式，這個值是經過驗證的
	REG32(spi, SPI_REG_SCKDIV) = (F_CLK / 300000UL); // 低速用於初始化
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
	dputs("ACMD41");
	do {
		sd_cmd55();
		r = sd_cmd(0x69, 0x40000000, 0x77); /* HCS = 1 */
	} while (r == 0x01);
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

	kprintf("\nPAYLOAD_SIZE_B 0x%x (%d sectors)\n", PAYLOAD_SIZE_B, PAYLOAD_SIZE);
#if SD_SPEED_OPTIMIZE
	kprintf("LOADING with SPEED OPTIMIZATIONS...\n");
#else
	kprintf("LOADING...\n");
#endif

	// 🚀 優化1: 提高SPI時鐘頻率
#if SD_SPEED_OPTIMIZE	
	REG32(spi, SPI_REG_SCKDIV) = 1; // 嘗試更高頻率（比 F_CLK/2 更快）
	kprintf("SPI Clock: OPTIMIZED (HIGHER)\n");
#else
	REG32(spi, SPI_REG_SCKDIV) = (F_CLK / 2UL); // 原來的設定
	kprintf("SPI Clock: STANDARD (F_CLK/2)\n");
#endif

	if (sd_cmd(0x52, BBL_PARTITION_START_SECTOR, 0xE1) != 0x00) {
		sd_cmd_end();
		return 1;
	}
	
	do {
		uint16_t crc, crc_exp;

		crc = 0;
		while (sd_dummy() != 0xFE);
		
#if SD_SPEED_OPTIMIZE
		// 🚀 優化2: 批量讀取整個扇區
		uint8_t sector_buf[SECTOR_SIZE_B];
		spi_read_bulk(sector_buf, SECTOR_SIZE_B);
		
		// 計算CRC並複製到目標
		for (int j = 0; j < SECTOR_SIZE_B; j++) {
			uint8_t x = sector_buf[j];
			*p++ = x;
			crc = crc16_round(crc, x);
		}
#else
		// 原來的逐字節方式
		long n = SECTOR_SIZE_B;
		do {
			uint8_t x = sd_dummy();
			*p++ = x;
			crc = crc16_round(crc, x);
		} while (--n > 0);
#endif

		crc_exp = ((uint16_t)sd_dummy() << 8);
		crc_exp |= sd_dummy();

		if (crc != crc_exp) {
			kputs("\b- CRC mismatch ");
			rc = 1;
			break;
		}

		// 🚀 優化3: 減少spinner更新頻率
#if SD_SPEED_OPTIMIZE
		// 每64個扇區才更新一次 (減少I/O開銷)
		if ((i & 63) == 0) {
			kputc('\b');
			kputc(spinner[SPIN_INDEX(i >> 6)]);
		}
#else
		// 原來的更新頻率
		if (SPIN_UPDATE(i)) {
			kputc('\b');
			kputc(spinner[SPIN_INDEX(i)]);
		}
#endif
	} while (--i > 0);
	
	sd_cmd_end();

	sd_cmd(0x4C, 0, 0x01);
	sd_cmd_end();
	kputs("\b ");
	
#if SD_SPEED_OPTIMIZE
	kprintf("\nOptimized copy completed!\n");
#else
	kprintf("\nStandard copy completed.\n");
#endif
	
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

__attribute__((naked, noinline)) void ddr_payload(void) {
    kputln("Hello DDR");
    while (1);  // 讓它停住
}

typedef void (*func_ptr_t)(void);
#define DDR_BASE 0x80000000
#define TEST_PATTERN 0x5A5A5A5A

void test_ddr_rw() {
    volatile uint32_t *ddr_ptr = (volatile uint32_t *)DDR_BASE;

    // Write a test pattern to DDR
    ddr_ptr[0] = TEST_PATTERN;
    ddr_ptr[1] = ~TEST_PATTERN;

    // Read back and verify
    if (ddr_ptr[0] != TEST_PATTERN) {
        kputln("DDR Test Failed @ addr 0x80000000");
        return;
    }

    if (ddr_ptr[1] != ~TEST_PATTERN) {
        kputln("DDR Test Failed @ addr 0x80000004");
        return;
    }

    kputln("DDR R/W PASS");
}

__attribute__((naked, noinline)) void ddr_test_payload(void) {
    volatile uint32_t *tx = (volatile uint32_t *)(UART_CTRL_ADDR + UART_REG_TXFIFO);
    *tx = 'D';
    *tx = 'D';
    *tx = 'R';
    while(1);
}


int main(void)
{
	uart_init();
	kputln("SD Boot v1.0");	
	sd_poweron();
	kputln("TL_CLK:");
	print_dec(F_CLK);

	if (sd_cmd0() ||
	    sd_cmd8() ||
	    sd_acmd41() ||
	    sd_cmd58() ||
	    sd_cmd16() ||
	    copy()) {
		kputln("ERROR");
		return 1;
	}	

	kputln("Jumping to OS...");
	// Fence.I 確保DDR已經寫完
    __asm__ __volatile__ ("fence.i" : : : "memory");
 
    // test_ddr_rw(); // jump to DDR test function

	return 0;
}