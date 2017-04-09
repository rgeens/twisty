#include <stddef.h>
#include "glk.h"
#include "config.h"
#include "compiler.h"
#include "git.h"
#include "memory.h"


void git_accel_c_shutdown();
void git_compiler_c_shutdown();
void git_glkop_c_shutdown();
void git_opcodes_c_shutdown();
void git_saveundo_c_shutdown();


void glk_shutdown() {
    // Global variables

    // compiler
    gPeephole = 1;
    gDebug = 0;
    gCacheRAM = 0;
    // sBuffer, sCodeStart, sCodeTop, sTempStart, sTempEnd, gHashTable, gBlockHeader
    shutdownCompiler();
    gHashSize = 0;

    // git
    gStackPointer = 0;

    // memory
    shutdownMemory();

    // Static variables
    git_accel_c_shutdown();
    git_compiler_c_shutdown();
    heap_clear();
    git_glkop_c_shutdown();
    git_opcodes_c_shutdown();
    resetPeepholeOptimiser();
    shutdownUndo();
}
