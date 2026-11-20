# Girag NFC – puente AppSheet → NFC

App Android mínima que graba etiquetas NTAG213/215/216 desde un enlace de AppSheet.

## Enlace desde AppSheet
```
giragnfc://grabar?doc=<ENCODEURL([Documento])>&pwd=<ENCODEURL([Clave])>
```
- Use `ENCODEURL()` en ambos valores (un `+` o `&` sin codificar rompe el valor).
- `pwd` opcional. 8 caracteres hex (p. ej. `A1B2C3D4`) se usan como contraseña NTAG directa; cualquier otro texto se convierte con SHA‑256 (solo 32 bits efectivos).
- Recomendado: una clave hex aleatoria de 8 caracteres distinta por etiqueta/empresa, no una palabra.

## Seguridad
- Se graba solo cuando el usuario acerca una etiqueta (nunca automáticamente al abrir el enlace).
- La protección es solo escritura (lectura libre); AUTHLIM=7 bloquea fuerza bruta.
- La contraseña viaja en la URL local (intent); se borra de memoria tras grabar.
- Cualquier app/página puede abrir el enlace `giragnfc://`; el usuario ve el documento en pantalla antes de acercar la etiqueta.

## Descarga
Ver la pestaña **Releases** del repositorio (APK firmado, generado por GitHub Actions).
