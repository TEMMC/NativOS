#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdint.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <errno.h>
#include <stdio.h>

static jlong failure(int error) { return static_cast<jlong>(-(error > 0 ? error : EIO)); }

extern "C" JNIEXPORT jlong JNICALL
Java_com_mt_gnome_TerminalActivity_nativeOpenPty(JNIEnv *env, jobject, jstring command, jint columns, jint rows) {
    const char *cmd = env->GetStringUTFChars(command, nullptr);
    if (!cmd) return failure(ENOMEM);
    // Unlock ptmx directly: grantpt() may fail on Android devpts configurations.
    int master = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) { int e = errno; env->ReleaseStringUTFChars(command, cmd); return failure(e); }
    int unlock = 0;
    if (ioctl(master, TIOCSPTLCK, &unlock) != 0) { int e = errno; close(master); env->ReleaseStringUTFChars(command, cmd); return failure(e); }
    int number = -1;
    if (ioctl(master, TIOCGPTN, &number) != 0) { int e = errno; close(master); env->ReleaseStringUTFChars(command, cmd); return failure(e); }
    char slaveName[64]; snprintf(slaveName, sizeof(slaveName), "/dev/pts/%d", number);
    int slave = open(slaveName, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slave < 0) { int e = errno; close(master); env->ReleaseStringUTFChars(command, cmd); return failure(e); }
    struct termios term{};
    if (tcgetattr(slave, &term) == 0) {
        term.c_iflag |= ICRNL | IXON; term.c_oflag |= OPOST | ONLCR; term.c_cflag |= CREAD;
        term.c_lflag |= ECHO | ECHOE | ECHOK | ICANON | ISIG | IEXTEN;
        term.c_cc[VERASE] = 0x7f; term.c_cc[VEOF] = 0x04; tcsetattr(slave, TCSANOW, &term);
    }
    struct winsize size{}; size.ws_col = columns > 0 ? columns : 80; size.ws_row = rows > 0 ? rows : 24;
    ioctl(slave, TIOCSWINSZ, &size);
    pid_t pid = fork();
    if (pid == 0) {
        close(master); if (setsid() < 0) _exit(126); if (ioctl(slave, TIOCSCTTY, 0) < 0) _exit(126);
        dup2(slave, STDIN_FILENO); dup2(slave, STDOUT_FILENO); dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        setenv("TERM", "xterm-256color", 1); setenv("COLORTERM", "truecolor", 1);
        execl("/system/bin/su", "su", "-c", cmd, (char *)nullptr);
        execlp("su", "su", "-c", cmd, (char *)nullptr); _exit(127);
    }
    int forkError = errno; close(slave); env->ReleaseStringUTFChars(command, cmd);
    if (pid < 0) { close(master); return failure(forkError); }
    uint64_t packed = (static_cast<uint64_t>(static_cast<uint32_t>(pid)) << 32) | static_cast<uint32_t>(master);
    return static_cast<jlong>(packed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mt_gnome_TerminalActivity_nativeResizePty(JNIEnv *, jobject, jint fd, jint columns, jint rows) {
    if (fd < 0) return; struct winsize size{}; size.ws_col = columns > 0 ? columns : 80; size.ws_row = rows > 0 ? rows : 24; ioctl(fd, TIOCSWINSZ, &size);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mt_gnome_TerminalActivity_nativeClosePty(JNIEnv *, jobject, jint fd, jint pid) {
    (void)fd; if (pid > 0) { kill(-pid, SIGHUP); kill(pid, SIGTERM); int status = 0; while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {} }
}
