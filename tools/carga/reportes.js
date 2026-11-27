// Prueba de carga de H6: los reportes que se ven en la junta del miércoles y las bandejas de
// abastecimiento, con varios usuarios a la vez. Criterio: p95 < 500 ms y sin errores.
//
//   make carga                                  (contra make dev, usuario admin)
//   BASE=https://staging… USUARIO=… CONTRASENA=… k6 run tools/carga/reportes.js
//
// Solo lee: no crea ni cambia nada, así que se puede correr contra staging con datos migrados.
import http from 'k6/http';
import { check, fail, group } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const USUARIO = __ENV.USUARIO || 'admin';
const CONTRASENA = __ENV.CONTRASENA || 'admin-local-2026';

export const options = {
  scenarios: {
    junta: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '20s', target: Number(__ENV.VUS || 20) },
        { duration: __ENV.DURACION || '1m', target: Number(__ENV.VUS || 20) },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{tipo:reporte}': ['p(95)<500'],
    'http_req_duration{tipo:bandeja}': ['p(95)<500'],
    checks: ['rate>0.99'],
  },
};

function iniciarSesion() {
  const jar = http.cookieJar();
  http.get(`${BASE}/api/v1/auth/csrf`, { tags: { tipo: 'sesion' } });
  const token = jar.cookiesForURL(BASE)['XSRF-TOKEN'];
  const r = http.post(
    `${BASE}/api/v1/auth/login`,
    JSON.stringify({ usuario: USUARIO, contrasena: CONTRASENA }),
    {
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token ? token[0] : '' },
      tags: { tipo: 'sesion' },
    },
  );
  if (r.status !== 200) {
    fail(`No se pudo iniciar sesión como ${USUARIO}: ${r.status} ${r.body}`);
  }
}

export function setup() {
  iniciarSesion();
  const r = http.get(`${BASE}/api/v1/reportes/contratos`, { tags: { tipo: 'sesion' } });
  if (r.status !== 200) {
    fail(`El usuario ${USUARIO} no ve el costo por contrato: ${r.status}`);
  }
  const contratos = r.json().map((c) => c.contratoId);
  if (contratos.length === 0) {
    fail('No hay contratos: migra el Excel o crea datos antes de medir');
  }
  return { contratos };
}

let conSesion = false;

function leer(nombre, ruta, tipo) {
  const r = http.get(`${BASE}${ruta}`, { tags: { name: nombre, tipo } });
  check(r, { [`${nombre} 200`]: (x) => x.status === 200 });
}

export default function (datos) {
  if (!conSesion) {
    iniciarSesion();
    conSesion = true;
  }
  const contrato = datos.contratos[(__VU + __ITER) % datos.contratos.length];

  group('reportes', () => {
    leer('costo por contrato', '/api/v1/reportes/contratos', 'reporte');
    leer('costo por lote', `/api/v1/reportes/contratos/${contrato}/lotes`, 'reporte');
    leer('costo por paquete', `/api/v1/reportes/costo?agrupar=paquete&contratoId=${contrato}`, 'reporte');
    leer('costo por clasificación', '/api/v1/reportes/costo?agrupar=clasificacion', 'reporte');
    leer('costo por mes', '/api/v1/reportes/costo?agrupar=mes', 'reporte');
    leer('presupuesto', `/api/v1/reportes/presupuesto?contratoId=${contrato}`, 'reporte');
    leer('avisos de presupuesto', '/api/v1/reportes/presupuesto?avisos=true', 'reporte');
    leer('valor del inventario', '/api/v1/reportes/valor-inventario', 'reporte');
    leer('requisición vs surtido', `/api/v1/reportes/requisicion-vs-surtido?contratoId=${contrato}`, 'reporte');
  });
  group('bandejas', () => {
    leer('requisiciones', '/api/v1/requisiciones', 'bandeja');
    leer('por autorizar', '/api/v1/requisiciones/por-autorizar', 'bandeja');
    leer('faltantes', '/api/v1/faltantes', 'bandeja');
    leer('pedidos', '/api/v1/pedidos', 'bandeja');
  });
}
