// P0.1 probe: dlopen() a plugin by path and call it, like gcc/as/ld do with xtensa_esp32.so.
#include <dlfcn.h>
#include <stdio.h>

int main(int argc, char **argv) {
    if (argc < 2) { printf("usage: dlprobe <plugin.so>\n"); return 2; }
    void *h = dlopen(argv[1], RTLD_LAZY);
    if (!h) { printf("dlopen failed: %s\n", dlerror()); return 3; }
    int (*answer)(void) = (int (*)(void)) dlsym(h, "probe_answer");
    if (!answer) { printf("dlsym failed: %s\n", dlerror()); return 4; }
    printf("dlopen-ok answer=%d\n", answer());
    return 0;
}
