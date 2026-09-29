package ffm;

/** Milestone 1: start CPython from pure Java, run code, finalize cleanly. */
public class M1 {
    public static void main(String[] args) {
        System.out.println("libpython: " + Py.LIBPYTHON);
        Py.start();
        System.out.println("Python version: " + Py.version().lines().findFirst().orElse("?"));
        try (Py.Gil gil = Py.Gil.lock()) {
            Py.exec("import sys; print('hello from python', sys.version_info[:2])");
        }
        int rc = Py.stop();
        System.out.println("Py_FinalizeEx rc=" + rc);
        if (rc != 0) System.exit(1);
        System.out.println("M1 OK");
    }
}
