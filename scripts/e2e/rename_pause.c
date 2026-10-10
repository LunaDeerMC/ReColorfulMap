/* Test-only crash injection for the real migration CLI subprocess.
 * Stops immediately after one selected atomic rename, before its caller can
 * complete the transaction. Never used by the production migration entrypoint.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int rename(const char *old_path, const char *new_path) {
    static int (*real_rename)(const char *, const char *);
    if (!real_rename) real_rename = dlsym(RTLD_NEXT, "rename");
    int result = real_rename(old_path, new_path);
    const char *selected = getenv("RCM_E2E_PAUSE_RENAME");
    if (result == 0 && selected && strcmp(selected, new_path) == 0) {
        const char *marker = getenv("RCM_E2E_PAUSE_MARKER");
        if (marker) {
            int descriptor = open(marker, O_CREAT | O_WRONLY | O_TRUNC, 0600);
            if (descriptor >= 0) {
                write(descriptor, "rename-complete\n", 16);
                fsync(descriptor);
                close(descriptor);
            }
        }
        raise(SIGSTOP);
    }
    return result;
}
