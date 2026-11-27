import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { ChevronLeft, ChevronRight, ShoppingCart } from 'lucide-react'
import { useState } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Insignia } from '@/components/ui/insignia'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { consultasCatalogo } from '@/features/catalogo/consultas'
import { consultasInventario, formatearDia } from '@/features/inventario/consultas'
import { useDiferido } from '@/lib/diferido'
import { formatearMoneda, formatearNumero } from '@/lib/formato'
import { PERMISOS, puede, useSesion } from '@/sesion/sesion'
import { armarPedido } from './calculos'
import { EstadoPedido } from './Comunes'
import {
  consultasAbastecimiento,
  ESTADOS_PEDIDO,
  TAMANO_PAGINA,
  type DatosPedido,
  type GrupoFaltantes,
} from './consultas'
import { EditorPedido, type PedidoInicial } from './EditorPedido'

/** Compras: lo que falta comprar por proveedor sugerido, y los pedidos hechos. */
export function PantallaPedidos() {
  const sesion = useSesion()
  const compra = puede(sesion, PERMISOS.pedidoCrear)
  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Pedidos a proveedor</h1>
        <p className="text-tenue">
          Los faltantes salen de las requisiciones autorizadas que el almacén no cubre. Al recibir
          contra el pedido, lo que llega se aparta para la requisición más urgente.
        </p>
      </header>
      {compra && <Faltantes />}
      <ListaPedidos />
    </div>
  )
}

function Faltantes() {
  const cliente = useQueryClient()
  const navegar = useNavigate()
  const [almacen, setAlmacen] = useState('')
  const almacenes = useQuery(consultasInventario.almacenes)
  const grupos = useQuery(
    consultasAbastecimiento.faltantes(undefined, almacen ? Number(almacen) : undefined),
  )
  const [elegidos, setElegidos] = useState<Set<number>>(new Set())
  const [armando, setArmando] = useState<{ grupo: GrupoFaltantes; inicial: PedidoInicial }>()
  const [aviso, setAviso] = useState<string>()

  const crear = useMutation({
    mutationFn: async (datos: DatosPedido) =>
      exigir(await api.POST('/api/v1/pedidos', { body: datos })),
    onSuccess: (p) => {
      void cliente.invalidateQueries({ queryKey: ['faltantes'] })
      void cliente.invalidateQueries({ queryKey: ['pedidos'] })
      void cliente.invalidateQueries({ queryKey: ['requisiciones'] })
      void navegar({ to: '/pedidos/$id', params: { id: String(p.id) } })
    },
  })

  const armar = (grupo: GrupoFaltantes) => {
    const deEste = grupo.faltantes.filter((f) => elegidos.has(f.requisicionLineaId))
    const lista = deEste.length > 0 ? deEste : grupo.faltantes
    const almacenesElegidos = new Set(lista.map((f) => f.almacenId))
    if (almacenesElegidos.size > 1) {
      setAviso('Un pedido llega a un solo almacén: elige faltantes de un mismo almacén.')
      return
    }
    setAviso(undefined)
    setArmando({
      grupo,
      inicial: {
        proveedorId: grupo.proveedorId,
        almacenId: lista[0]?.almacenId,
        renglones: armarPedido(lista),
      },
    })
  }

  if (armando) {
    return (
      <EditorPedido
        titulo={`Pedido a ${armando.grupo.proveedor}`}
        inicial={armando.inicial}
        guardando={crear.isPending}
        error={crear.isError ? mensajeDe(crear.error) : undefined}
        alGuardar={(datos) => crear.mutate(datos)}
        alCancelar={() => setArmando(undefined)}
      />
    )
  }

  const cambiar = (id: number, si: boolean) => {
    const siguiente = new Set(elegidos)
    if (si) siguiente.add(id)
    else siguiente.delete(id)
    setElegidos(siguiente)
  }

  return (
    <section className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <h2 className="text-lg font-medium">Falta comprar</h2>
        <div className="w-64">
          <Seleccion
            etiqueta="Almacén"
            vacio="Todos"
            opciones={(almacenes.data ?? []).map((a) => ({ valor: a.id, texto: a.clave }))}
            value={almacen}
            onChange={(e) => setAlmacen(e.target.value)}
          />
        </div>
      </div>
      {grupos.isError && <Aviso>{mensajeDe(grupos.error)}</Aviso>}
      {aviso && <Aviso>{aviso}</Aviso>}
      {grupos.data?.length === 0 && (
        <Tarjeta>
          <p className="text-tenue">
            No falta nada: lo autorizado está apartado, en vale o ya pedido.
          </p>
        </Tarjeta>
      )}
      {grupos.data?.map((grupo) => (
        <Tarjeta key={grupo.proveedorId ?? 'sin'} className="p-0">
          <div className="flex flex-wrap items-center justify-between gap-2 px-4 pt-4">
            <h3 className="font-medium">{grupo.proveedor}</h3>
            <Boton onClick={() => armar(grupo)}>
              <ShoppingCart className="size-4" /> Armar pedido
              {grupo.faltantes.some((f) => elegidos.has(f.requisicionLineaId))
                ? ' con lo elegido'
                : ''}
            </Boton>
          </div>
          <Tabla
            filas={grupo.faltantes}
            clave={(f) => f.requisicionLineaId}
            columnas={[
              {
                titulo: 'Elegir',
                celda: (f) => (
                  <input
                    type="checkbox"
                    className="size-5"
                    aria-label={`Elegir ${f.folio} ${f.unidadObra} ${f.material}`}
                    checked={elegidos.has(f.requisicionLineaId)}
                    onChange={(e) => cambiar(f.requisicionLineaId, e.target.checked)}
                  />
                ),
              },
              {
                titulo: 'Requisición',
                celda: (f) => (
                  <Link
                    to="/requisiciones/$id"
                    params={{ id: String(f.requisicionId) }}
                    className="font-mono text-marca hover:underline"
                  >
                    {f.folio}
                  </Link>
                ),
              },
              {
                titulo: 'Para el',
                celda: (f) => (
                  <span className="inline-flex items-center gap-2 whitespace-nowrap">
                    {formatearDia(f.fechaRequerida)}
                    {f.urgente && <Insignia>Urgente</Insignia>}
                  </span>
                ),
              },
              { titulo: 'Lote', celda: (f) => `${f.contrato} ${f.unidadObra}` },
              { titulo: 'Material', celda: (f) => f.material },
              {
                titulo: 'Falta',
                celda: (f) => `${formatearNumero(f.faltante)} ${f.unidadControl}`,
                clase: 'text-right whitespace-nowrap',
              },
              {
                titulo: 'Último precio',
                celda: (f) =>
                  f.precioReferencia == null ? '—' : formatearMoneda(f.precioReferencia),
                clase: 'text-right',
              },
              {
                titulo: 'Sugerido por',
                celda: (f) =>
                  f.origenSugerencia === 'excel'
                    ? 'Histórico (Excel)'
                    : f.origenSugerencia === 'sistema'
                      ? 'Última compra'
                      : '—',
                clase: 'text-tenue',
              },
            ]}
          />
        </Tarjeta>
      ))}
    </section>
  )
}

function ListaPedidos() {
  const [estado, setEstado] = useState('')
  const [proveedor, setProveedor] = useState('')
  const [folio, setFolio] = useState('')
  const [pagina, setPagina] = useState(0)
  const folioDiferido = useDiferido(folio.trim(), 250)
  const proveedores = useQuery(consultasCatalogo.proveedores())
  const pedidos = useQuery(
    consultasAbastecimiento.pedidos({
      estados: estado || undefined,
      proveedorId: proveedor ? Number(proveedor) : undefined,
      folio: folioDiferido || undefined,
      pagina,
    }),
  )
  const filtrar = (cambio: () => void) => {
    cambio()
    setPagina(0)
  }
  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-lg font-medium">Pedidos</h2>
      <div className="grid gap-4 md:grid-cols-3 md:items-end">
        <Seleccion
          etiqueta="Estado"
          vacio="Todos"
          opciones={Object.entries(ESTADOS_PEDIDO).map(([valor, texto]) => ({ valor, texto }))}
          value={estado}
          onChange={(e) => filtrar(() => setEstado(e.target.value))}
        />
        <Seleccion
          etiqueta="Proveedor"
          vacio="Todos"
          opciones={(proveedores.data ?? []).map((p) => ({ valor: p.id, texto: p.nombre }))}
          value={proveedor}
          onChange={(e) => filtrar(() => setProveedor(e.target.value))}
        />
        <Campo
          etiqueta="Folio"
          type="search"
          placeholder="PED-2026-000012"
          value={folio}
          onChange={(e) => filtrar(() => setFolio(e.target.value))}
        />
      </div>
      <Tarjeta className="p-0">
        {pedidos.isError && (
          <div className="p-4">
            <Aviso>{mensajeDe(pedidos.error)}</Aviso>
          </div>
        )}
        <Tabla
          filas={pedidos.data}
          clave={(p) => p.id}
          vacio="No hay pedidos con esos filtros."
          columnas={[
            {
              titulo: 'Folio',
              celda: (p) => (
                <Link
                  to="/pedidos/$id"
                  params={{ id: String(p.id) }}
                  className="font-mono text-marca hover:underline"
                >
                  {p.folio}
                </Link>
              ),
              clase: 'whitespace-nowrap',
            },
            { titulo: 'Proveedor', celda: (p) => p.proveedor },
            { titulo: 'Almacén', celda: (p) => p.almacen },
            { titulo: 'Fecha', celda: (p) => formatearDia(p.fecha), clase: 'whitespace-nowrap' },
            { titulo: 'Entrega', celda: (p) => formatearDia(p.fechaEntrega) },
            { titulo: 'Renglones', celda: (p) => p.renglones, clase: 'text-right' },
            {
              titulo: 'Subtotal',
              celda: (p) => (p.subtotal == null ? '—' : formatearMoneda(p.subtotal)),
              clase: 'text-right whitespace-nowrap',
            },
            { titulo: 'Estado', celda: (p) => <EstadoPedido estado={p.estado} /> },
          ]}
        />
        <div className="flex items-center justify-end gap-2 border-t border-borde px-4 py-2 text-sm text-tenue">
          <Boton
            variante="fantasma"
            aria-label="Página anterior"
            disabled={pagina === 0}
            onClick={() => setPagina(pagina - 1)}
          >
            <ChevronLeft className="size-4" />
          </Boton>
          <span>Página {pagina + 1}</span>
          <Boton
            variante="fantasma"
            aria-label="Página siguiente"
            disabled={(pedidos.data?.length ?? 0) < TAMANO_PAGINA}
            onClick={() => setPagina(pagina + 1)}
          >
            <ChevronRight className="size-4" />
          </Boton>
        </div>
      </Tarjeta>
    </section>
  )
}
