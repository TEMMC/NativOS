#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdint.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <string>
#include <errno.h>

extern "C" JNIEXPORT jlong JNICALL
Java_com_mt_gnome_TerminalActivity_nativeOpenPty(JNIEnv *env, jobject, jstring command, jint columns, jint rows) {
    const char *cmd = env->GetStringUTFChars(command, nullptr);
    if (!cmd) return -1;
    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0 || grantpt(master) != 0 || unlockpt(master) != 0) {
        if (master >= 0) close(master);
        env->ReleaseStringUTFChars(command, cmd);
        return -1;
    }
    char *slaveName = ptsname(master);
    if (!slaveName) {
        close(master);
        env->ReleaseStringUTFChars(command, cmd);
        return -1;
    }
    int slave = open(slaveName, O_RDWR | O_NOCTTY);
    if (slave < 0) {
        close(master);
        env->ReleaseStringUTFChars(command, cmd);
        return -1;
    }
    struct winsize size{};
    size.ws_col = columns > 0 ? columns : 80;
    size.ws_row = rows > 0 ? rows : 24;
    ioctl(slave, TIOCSWINSZ, &size);
    pid_t pid = fork();
    if (pid == 0) {
        close(master);
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        execlp("su", "su", "-c", cmd, (char *)nullptr);
        _exit(127);
    }
    close(slave);
    env->ReleaseStringUTFChars(command, cmd);
    if (pid < 0) {
        close(master);
        return -1;
    }
    uint64_t packed = (static_cast<uint64_t>(static_cast<uint32_t>(pid)) << 32) |
                      static_cast<uint32_t>(master);
    return static_cast<jlong>(packed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mt_gnome_TerminalActivity_nativeResizePty(JNIEnv *, jobject, jint fd, jint columns, jint rows) {
    if (fd < 0) return;
    struct winsize size{};
    size.ws_col = columns > 0 ? columns : 80;
    size.ws_row = rows > 0 ? rows : 24;
    ioctl(fd, TIOCSWINSZ, &size);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mt_gnome_TerminalActivity_nativeClosePty(JNIEnv *, jobject, jint fd, jint pid) {
    (void)fd; // ParcelFileDescriptor owns and closes the PTY master.
    if (pid > 0) {
        kill(-pid, SIGHUP);
        kill(pid, SIGTERM);
        int status = 0;
        while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
    }
}
