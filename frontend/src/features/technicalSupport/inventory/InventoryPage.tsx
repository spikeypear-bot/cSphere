import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { PageHeader } from '../../../components/ui/PageHeader'
import { EQUIPMENT_MEDIA, equipmentMediaFor } from '../../equipment/equipmentCatalogueMedia'
import { EquipmentPhoto } from '../damage/EquipmentPhoto'
import '../damage/equipmentDamage.css'
import { DateRangeFields } from '../shared/DateRangeFields'
import { rangeFromParams, rangeToWindow } from '../shared/dateWindow'
import { equipmentPath } from '../shared/faultyEquipment'
import { ResourceView } from '../../../components/ui/ResourceView'
import { RowLink } from '../../../components/ui/RowLink'
import { useResource } from '../../../lib/useResource'
import { fetchCatalogueForWindow } from './inventoryApi'
import '../technicalSupport.css'

/** Categories come from the catalogue's media map (not the database, which
 * only stores the general code 'E'); kinds without an entry are "Other". */
const CATEGORIES = [...new Set(Object.values(EQUIPMENT_MEDIA).map((media) => media.category))]
const OTHER = 'Other'
const categoryOf = (name: string) => equipmentMediaFor(name)?.category ?? OTHER

/** Every kind with what is reserved, out of action and left for a window. */
export function InventoryPage() {
  const [params, setParams] = useSearchParams()
  const range = rangeFromParams(params)
  const span = rangeToWindow(range)
  const [category, setCategory] = useState('')
  const [search, setSearch] = useState('')
  const catalogue = useResource(
    () => (span ? fetchCatalogueForWindow(span.from, span.to) : Promise.resolve([])),
    span ? `${span.from}|${span.to}` : 'invalid',
  )
  const query = `from=${range.from}&to=${range.to}`

  return (
    <section className="page">
      <PageHeader title="Inventory" description="What is reserved, out of action and still available over the dates you choose." />
      <div className="tech-filters">
        <DateRangeFields value={range} onChange={(next) => setParams({ from: next.from, to: next.to }, { replace: true })} />
        <div className="field">
          <label htmlFor="inventory-category">Category</label>
          <select id="inventory-category" value={category} onChange={(e) => setCategory(e.target.value)}>
            <option value="">All categories</option>
            {[...CATEGORIES, OTHER].map((name) => <option key={name} value={name}>{name}</option>)}
          </select>
        </div>
        <div className="field">
          <label htmlFor="inventory-search">Search</label>
          <input id="inventory-search" type="search" value={search} onChange={(e) => setSearch(e.target.value)} />
        </div>
      </div>
      {span ? (
        <ResourceView resource={catalogue} loadingText="Loading inventory…"
          errorText="The inventory could not be loaded." emptyText="The equipment catalogue is empty."
          isEmpty={(items) => items.length === 0}>
          {(items) => {
            const needle = search.trim().toLowerCase()
            const shown = items.filter((item) => (!category || categoryOf(item.equipmentName) === category)
              && (!needle || item.equipmentName.toLowerCase().includes(needle)))
            if (shown.length === 0) return <p className="field-hint">No equipment matches these filters.</p>
            return (
              <ul className="tech-kinds" aria-label="Equipment kinds">
                {shown.map((item) => (
                  <li key={item.equipmentId}>
                    <RowLink to={`${equipmentPath(item.equipmentId)}?${query}`} className="card tech-kind" titleAs="h2"
                      title={item.equipmentName} media={<EquipmentPhoto name={item.equipmentName} />}>
                      <p className="tech-row-card__hint">{categoryOf(item.equipmentName)} · {item.serialised ? 'tracked by unit' : 'counted by quantity'}</p>
                      <dl className="tech-figures">
                        <div><dt>Total</dt><dd>{item.totalQuantity}</dd></div>
                        <div><dt>Reserved</dt><dd>{item.reservedQuantity}</dd></div>
                        <div><dt>Out of action</dt><dd>{item.outOfActionQuantity}</dd></div>
                        <div><dt>Available</dt><dd>{item.availableQuantity}</dd></div>
                      </dl>
                    </RowLink>
                  </li>
                ))}
              </ul>
            )
          }}
        </ResourceView>
      ) : null}
    </section>
  )
}
