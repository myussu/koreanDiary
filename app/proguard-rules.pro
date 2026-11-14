# Reglas adicionales para release. ML Kit y Room traen sus propias reglas de consumidor.

# La base vive en :shared con @ConstructedBy. Según la guía de Room para KMP, con minify hace falta
# conservar el constructor de la implementación generada.
-keep class * extends androidx.room3.RoomDatabase { <init>(); }

# Archivo y línea en los errores: con el mapping.txt de la compilación (artefacto de Actions) se
# puede leer dónde falló.
-keepattributes SourceFile,LineNumberTable
