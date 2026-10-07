/*
 * Live configuration over USB (KishiDS).  Carried on HID feature report 0xAC, which the DS4
 * descriptor already declares (58 bytes: ID + 57).  Nothing else uses it, and every SET must carry
 * the signature byte so a stray host write can never act as a command.
 *
 * SET_REPORT 0xAC  (host -> device):   [0xAC][0x4B][cmd][idx][data ...]
 *   cmd 1 STAGE    idx = chunk 0..7, data = 32 bytes of the config block (chunk idx covers bytes idx*32..+31)
 *   cmd 2 APPLY    all 8 chunks must have been staged; the block must pass magic/version/size/CRC.
 *                  On success it becomes the active configuration immediately (not saved).
 *   cmd 3 READSEL  idx = chunk the next GET returns
 *   cmd 4 SAVE     write the active configuration to the flash page (survives power cycles)
 *   cmd 5 ERASE    erase the saved record (next boot uses the image block / defaults)
 *   cmd 6 REBOOT   reset the controller (re-enumerates; needed for USB identity changes)
 *
 * GET_REPORT 0xAC  (device -> host, 58 bytes):
 *   [0]=0xAC [1]=protocol(1) [2]=status of last command [3]=last command
 *   [4..7]  active config CRC     [8..11] persisted config CRC (what the next boot loads)
 *   [12]    flags: b0 unsaved changes, b1 USB identity differs from the running enumeration,
 *                  b2 active config came from the saved record, b3 save/erase/reboot still pending
 *   [13]    selected read chunk   [16..47] that 32-byte chunk of the active config block
 *
 * Status: 0 ok, 1 bad/incomplete block, 2 bad command or argument, 3 flash write failed.
 */
#ifndef LIVE_H
#define LIVE_H

#include <stdint.h>

#include "config.h"

#define LIVE_REPORT_ID   0xAC
#define LIVE_SIGNATURE   0x4B
#define LIVE_PROTOCOL    1u
#define LIVE_REPORT_LEN  58u

enum live_cmd { LC_STAGE = 1, LC_APPLY = 2, LC_READSEL = 3, LC_SAVE = 4, LC_ERASE = 5, LC_REBOOT = 6 };
enum live_status { LS_OK = 0, LS_BAD_BLOCK = 1, LS_BAD_CMD = 2, LS_FLASH = 3 };

/* Telemetry flag bits (report 0xAB byte 2), set by live_telemetry_flags(). */
#define TELE_LIVE       0x04u   /* firmware speaks this protocol */
#define TELE_FROM_SAVED 0x08u
#define TELE_UNSAVED    0x10u
#define TELE_IDENT_DIFF 0x20u

/* Snapshot the boot-time configuration (identity fields are only read at USB enumeration). */
void live_init(void);

/* Handle a SET_REPORT: data[0] is the report ID.  Never blocks; flash/reboot work is deferred to live_service(). */
void live_set_report(const uint8_t *data, unsigned len);

/* Fill a GET_REPORT response (LIVE_REPORT_LEN bytes). */
void live_get_report(uint8_t *out);

/* Run deferred save/erase/reboot work.  Call from the main loop, outside the USB handler. */
void live_service(void);

/* Bits for the telemetry flags byte. */
uint8_t live_telemetry_flags(void);

/* Provided by the platform (persist.c on hardware, test stubs on the host). */
void hw_apply_config(void);                          /* re-derive anything cached from kcfg (calibration) */
int hw_flash_save(const struct kcfg_saved *r);   /* 0 on success */
int hw_flash_erase(void);                            /* 0 on success */
void hw_reboot(void);

#endif
