package clojure.lang;

public final class Synchronized {
    public static Object lock(final Object lockee, final IFn f) {
        synchronized(lockee) {
            return f.invoke();
        }
    }
}
