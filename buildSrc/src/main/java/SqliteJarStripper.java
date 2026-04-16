import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Strips unused native libraries from sqlite-jdbc JAR.
 * 
 * sqlite-jdbc bundles 24 platform/architecture combinations (~14 MB).
 * This utility keeps only:
 * - Linux x86_64
 * - macOS aarch64
 * - Windows x86_64
 * 
 * All other native libraries are removed, typically saving ~10 MB.
 */
public class SqliteJarStripper {
    
    public void strip(File inputJar, File outputJar) throws IOException {
        outputJar.getParentFile().mkdirs();
        
        long originalSize = 0;
        long strippedSize = 0;
        
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(inputJar));
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputJar))) {
            
            originalSize = inputJar.length();
            
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryPath = entry.getName();
                
                // Keep entries that are either:
                // 1. Not native libraries (don't start with org/sqlite/native/)
                // 2. Supported platform native libraries
                boolean keep = !entryPath.startsWith("org/sqlite/native/") ||
                    entryPath.contains("Linux/amd64/") ||
                    entryPath.contains("Mac/aarch64/") ||
                    entryPath.contains("Windows/x86_64/");
                
                if (keep) {
                    ZipEntry newEntry = new ZipEntry(entryPath);
                    newEntry.setTime(entry.getTime());
                    newEntry.setCompressedSize(-1);
                    
                    zos.putNextEntry(newEntry);
                    
                    if (!entry.isDirectory()) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            zos.write(buffer, 0, bytesRead);
                        }
                    }
                    
                    zos.closeEntry();
                }
            }
        }
        
        strippedSize = outputJar.length();
        long savedMB = (originalSize - strippedSize) / (1024 * 1024);
        long originalMB = originalSize / (1024 * 1024);
        long strippedMB = strippedSize / (1024 * 1024);
        
        System.out.println(String.format(
            "SQLite JAR stripped: %dMB → %dMB (−%dMB)", 
            originalMB, strippedMB, savedMB
        ));
    }
}
