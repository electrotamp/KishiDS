/*
 * Kishi V2 Pro rumble: two TI DRV2605-family haptics drivers (I2C address 0x5A), one per actuator, on FLEXCOMM1 and
 * FLEXCOMM4, driven in real-time-playback (RTP) mode as Razer's firmware 2.2 does (see haptics_v2pro.c).
 */
#ifndef HAPTICS_V2PRO_H
#define HAPTICS_V2PRO_H

#include <stdint.h>

/*
 * Call every main-loop pass with the free-running ms count and the host's latest rumble levels (DS4 output report
 * 0x05: strong = left / big motor, weak = right / small).  Initialises the drivers once, 250 ms after start-up, then
 * writes a side only when its level changes.
 */
void haptics_update(uint32_t ms, uint8_t strong, uint8_t weak);

/* Per side: the DRV2605 STATUS register read at init (bits 7..5 = device ID; 0xFF = no answer), for telemetry. */
extern uint8_t haptics_status[2];

#endif
