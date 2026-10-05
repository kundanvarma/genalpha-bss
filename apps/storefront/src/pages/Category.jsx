import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { listOfferings, priceIndex } from '../api.js';
import { initialData } from '../ssr-data.js';
import { t } from '../i18n.js';
import { OfferingCard } from './Shop.jsx';
import { LINES, ShelfLinks, itemsOn } from './lines.jsx';

/*
 * A SHELF WITH ITS OWN URL (#180).
 *
 * The shop groups offers by line of business, and until now that grouping lived
 * entirely in component state behind `?tab=`. A person could use it; a crawler
 * could not follow it, and an answer engine had no page to cite for "mobile
 * plans" at all. The ticket's phrase for this is that categories must be
 * crawlable pages, not application state.
 *
 * So each line of business is a real route with a real link to it, rendered on
 * the server with the offerings already resolved. The grouping rules are the
 * same ones the shop tabs use — one definition, imported here, so a shelf
 * cannot mean one thing on the tab and another on its own page.
 */

export default function Category() {
  const { slug } = useParams();
  const line = LINES.find((l) => l.slug === slug);
  const seeded = initialData();
  const [offerings, setOfferings] = useState(() => seeded?.offerings ?? null);
  const [prices, setPrices] = useState(() => seeded?.prices ?? {});

  useEffect(() => {
    if (offerings) return;              // the server already resolved this page
    listOfferings().then(setOfferings).catch(() => setOfferings([]));
    priceIndex().then(setPrices).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slug]);

  if (!line) {
    // An unknown shelf is not a shelf. Say so rather than render an empty one,
    // which reads to a crawler as a real page with no products.
    return (
      <section className="lobcard" data-testid="category-unknown">
        <h1>{t('Not found')}</h1>
        <p className="dim">{t('No such part of the shop.')}</p>
        <p><Link to="/">{t('Back to the shop')}</Link></p>
      </section>
    );
  }

  const items = itemsOn(line, offerings);

  return (
    <section className="lobcard" data-testid="category-page" data-category={line.slug}>
      <h1>{t(line.label)}</h1>
      {offerings === null && <p className="dim">{t('Loading…')}</p>}
      {offerings !== null && !items.length && (
        <p className="dim" data-testid="category-empty">{t('Nothing on this shelf right now.')}</p>
      )}
      <div className="cards">
        {items.map((o) => <OfferingCard key={o.id} offering={o} prices={prices} />)}
      </div>
      <ShelfLinks except={line.slug} testid="category-siblings" />
    </section>
  );
}
