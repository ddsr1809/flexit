package mx.constructora.organizacion.aplicacion;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifra el secreto del segundo factor con AES-256-GCM antes de guardarlo. La llave vive en el
 * servidor ({@code APP_LLAVE_SEGUNDO_FACTOR}), no en la base: un respaldo o un volcado no basta
 * para generar códigos. El id del usuario va como dato autenticado: un secreto copiado al renglón
 * de otro usuario no se descifra. Formato: 12 bytes de vector inicial, luego cifrado y etiqueta.
 */
final class CifradoSecreto {

  private static final int VECTOR = 12;
  private static final int ETIQUETA_BITS = 128;
  private static final SecureRandom AZAR = new SecureRandom();

  private final SecretKeySpec llave;

  private CifradoSecreto(byte[] llave) {
    this.llave = new SecretKeySpec(llave, "AES");
  }

  /**
   * @param base64 32 bytes en Base64 ({@code openssl rand -base64 32})
   * @throws IllegalArgumentException si falta o no mide 32 bytes
   */
  static CifradoSecreto desde(String base64) {
    if (base64 == null || base64.isBlank()) {
      throw new IllegalArgumentException("Falta APP_LLAVE_SEGUNDO_FACTOR");
    }
    byte[] llave;
    try {
      llave = Base64.getDecoder().decode(base64.strip());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("APP_LLAVE_SEGUNDO_FACTOR no es Base64", e);
    }
    if (llave.length != 32) {
      throw new IllegalArgumentException(
          "APP_LLAVE_SEGUNDO_FACTOR debe medir 32 bytes (openssl rand -base64 32)");
    }
    return new CifradoSecreto(llave);
  }

  byte[] cifrar(byte[] claro, long usuarioId) {
    byte[] vector = new byte[VECTOR];
    AZAR.nextBytes(vector);
    try {
      Cipher cifrador = Cipher.getInstance("AES/GCM/NoPadding");
      cifrador.init(Cipher.ENCRYPT_MODE, llave, new GCMParameterSpec(ETIQUETA_BITS, vector));
      cifrador.updateAAD(dueno(usuarioId));
      byte[] cifrado = cifrador.doFinal(claro);
      return ByteBuffer.allocate(VECTOR + cifrado.length).put(vector).put(cifrado).array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * @throws IllegalStateException si la llave es otra o el dato se alteró
   */
  byte[] descifrar(byte[] guardado, long usuarioId) {
    try {
      Cipher cifrador = Cipher.getInstance("AES/GCM/NoPadding");
      cifrador.init(
          Cipher.DECRYPT_MODE,
          llave,
          new GCMParameterSpec(ETIQUETA_BITS, Arrays.copyOfRange(guardado, 0, VECTOR)));
      cifrador.updateAAD(dueno(usuarioId));
      return cifrador.doFinal(guardado, VECTOR, guardado.length - VECTOR);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(
          "No se pudo descifrar el segundo factor: ¿cambió APP_LLAVE_SEGUNDO_FACTOR?", e);
    }
  }

  /** 20 bytes al azar: el tamaño que recomienda el RFC 4226 para HMAC-SHA1. */
  static byte[] nuevoSecreto() {
    byte[] secreto = new byte[20];
    AZAR.nextBytes(secreto);
    return secreto;
  }

  private static byte[] dueno(long usuarioId) {
    return ByteBuffer.allocate(Long.BYTES).putLong(usuarioId).array();
  }
}
