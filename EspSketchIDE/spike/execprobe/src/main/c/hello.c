// P0.1 probe: prints a marker, or with "exec <path> [args]" replaces itself with another
// program, which is what the gcc driver does when it starts cc1 and as.
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>

int main(int argc, char **argv) {
    if (argc >= 3 && strcmp(argv[1], "exec") == 0) {
        execv(argv[2], &argv[2]);
        printf("execv failed: %s\n", strerror(errno));
        return 3;
    }
    printf("hello-ok pid=%d\n", getpid());
    return 0;
}
