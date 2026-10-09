#ifndef PERSIST_H
#define PERSIST_H

/* Copy the saved settings record out of flash with a read that cannot fault; call before kcfg_load(). */
void persist_load(void);

#endif
