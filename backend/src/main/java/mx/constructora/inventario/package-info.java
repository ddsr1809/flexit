/**
 * Movimientos, kardex, saldo y costo promedio: el núcleo (hito H4). Solo puede usar los módulos
 * listados en {@code allowedDependencies}.
 */
@ApplicationModule(allowedDependencies = {"estructura", "catalogo", "organizacion", "compartido"})
package mx.constructora.inventario;

import org.springframework.modulith.ApplicationModule;
