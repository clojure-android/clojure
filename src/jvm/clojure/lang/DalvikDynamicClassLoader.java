/* Copyright © 2011 Sattvik Software & Technology Resources, Ltd. Co.
 * All rights reserved.
 *
 * The use and distribution terms for this software are covered by the Eclipse
 * Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
 * can be found in the file epl-v10.html at the root of this distribution.  By
 * using this software in any fashion, you are agreeing to be bound by the
 * terms of this license.  You must not remove this notice, or any other, from
 * this software.
 */
package clojure.lang;

import android.content.Context;
import android.util.Log;
import com.android.dx.cf.direct.DirectClassFile;
import com.android.dx.cf.direct.StdAttributeFactory;
import com.android.dx.dex.cf.CfOptions;
import com.android.dx.dex.cf.CfTranslator;
import com.android.dx.dex.DexOptions;
import dalvik.system.DexFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Dynamic class loader for the Dalvik VM.
 *
 * @since 1.2.0
 * @author Daniel Solano Gómez
 */
public class DalvikDynamicClassLoader extends DynamicClassLoader {
    /** Options for translation. */
    private static final CfOptions OPTIONS = new CfOptions();
    /** Reference to compile path var, used for generated jar files. */
    private static final Var COMPILE_PATH =
            RT.var("clojure.core", "*compile-path*");
    /** Configure whether or not to use extended op codes. */
    private static final DexOptions DEX_OPTIONS = new DexOptions();
    /** Directory where temporary class files will go. */
    private static File cacheDirectory;
    private static Context applicationContext;

    static {
        // disable name checks
        OPTIONS.strictNameCheck = false;
        // Use DEX format ver. 0.3.5 because ART rejects later versions.
        DEX_OPTIONS.targetApiLevel = 13;
    }

    /** Tag used for logging. */
    private static final String TAG = "DalvikClojureCompiler";

    public DalvikDynamicClassLoader() {
        super();
    }

    public DalvikDynamicClassLoader(final ClassLoader parent) {
        super(parent);
    }

    /**
     * Dalvik-specific method for dynamically loading a class from JVM byte
     * codes.  As there is no easy way to translate a class from the JVM to
     * Dalvik in-memory, this method takes a slow route through disk.  This
     * involves using a DexFile from the dx tool to translate the JVM class
     * into a Dalvik executable.  The contents of the executable must be
     * written to disk as an entry in a zip file.  This zip file is then loaded
     * and the requested class is instantiated using the Android runtime's
     * DexFile.
     *
     * @param name the name of the class to define
     * @param bytes the JVM bytecodes for the class
     * @param srcForm the Clojure form for the class
     */
    @Override
    protected Class<?> defineMissingClass(final String name, final byte[] bytes,
            final Object srcForm) {
        // create dx DexFile and add translated class into it
        final com.android.dx.dex.file.DexFile outDexFile =
                new com.android.dx.dex.file.DexFile(DEX_OPTIONS);
        final DirectClassFile cf = new DirectClassFile(bytes, asFilePath(name), false);
        cf.setAttributeFactory(StdAttributeFactory.THE_ONE);
        outDexFile.add(CfTranslator.translate(cf, bytes, OPTIONS, DEX_OPTIONS, outDexFile));

        // get compile directory
        if (cacheDirectory == null) {
            initializeDynamicCompilation();
        }
        final File compileDir = cacheDirectory;
        try {
            // write generated DEX into a file
            File tmpDexFile = File.createTempFile("repl-", ".dex", compileDir);
            FileOutputStream fos = new FileOutputStream(tmpDexFile);
            outDexFile.writeTo(fos, null, false);
            fos.close();

            final File optDexFile =
                new File(compileDir, tmpDexFile.getName().replace("repl-", "repl-opt-"));
            final DexFile inDexFile =
                DexFile.loadDex(tmpDexFile.getAbsolutePath(), optDexFile.getAbsolutePath(), 0);

            // load the class
            Class<?> clazz = inDexFile.loadClass(name.replace(".", "/"), this);
            if (clazz == null) {
                Log.e(TAG,"Failed to load generated class: "+name);
                throw new RuntimeException(
                        "Failed to load generated class " + name + ".");
            }
            return clazz;
        } catch (IOException e) {
            Log.e(TAG,"Failed to define class due to I/O exception.",e);
            throw new RuntimeException(e);
        }
    }

    private static String asFilePath(String name) {
        return name.replace('.', '/').concat(".class");
    }

    public void clearCache() {
        if (cacheDirectory != null) {
            synchronized(cacheDirectory) {
                for (File f : cacheDirectory.listFiles())
                    f.delete();
            }
        }
    }

    public InputStream getDataReadersStream() {
        if (applicationContext != null) {
            try {
                return applicationContext.getAssets().open("data_readers.clj");
            } catch (IOException e) {}
        }
        return null;
    }

    public void initializeDynamicCompilation() {
        cacheDirectory = new File(applicationContext.getCacheDir(), "clojure_repl");
        Log.d(TAG, "CACHED DIRECTORY: " + cacheDirectory);
        String path = cacheDirectory.getAbsolutePath();
        cacheDirectory.mkdir();
        clearCache();
        System.setProperty("clojure.compile.path", path);
        COMPILE_PATH.swapRoot(path);
        // Add URL resource for data readers initialization

    }

    public static void setContext(Context context) {
        applicationContext = context;
    }
}
