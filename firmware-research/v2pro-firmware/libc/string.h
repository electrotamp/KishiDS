/* Minimal freestanding <string.h>: the only C library functions this firmware, the shared V1 sources and TinyUSB use. */
#ifndef V2PRO_STRING_H
#define V2PRO_STRING_H

#include <stddef.h>

void *memcpy(void *dst, const void *src, size_t n);
void *memset(void *dst, int c, size_t n);
int memcmp(const void *a, const void *b, size_t n);

#endif
