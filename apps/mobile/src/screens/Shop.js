/*
 * The buy tab: browse the tenant's catalog; plan-only offerings order in
 * one tap and the SOM completes them in seconds — the app shows the whole
 * loop. Bundles/devices point to the full storefront configurator for now.
 */
import { useCallback, useState } from 'react';
import { Linking, ScrollView, Text } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { myOrders, offerings, orderOffering, prices } from '../api.js';
import { Button, Card, Dim, Row, palette } from '../ui.js';

function monthly(offering, priceIndex) {
  const total = (offering.productOfferingPrice || [])
    .map((ref) => priceIndex[ref.id])
    .filter((p) => p && p.priceType === 'recurring')
    .reduce((sum, p) => sum + Number(p.price.value), 0);
  return total > 0 ? total.toFixed(2) + ' EUR/mo' : null;
}

export default function Shop() {
  const [items, setItems] = useState([]);
  const [priceIndex, setPriceIndex] = useState({});
  const [orders, setOrders] = useState([]);
  const [message, setMessage] = useState(null);

  const load = useCallback(() => {
    offerings().then(setItems);
    prices().then(setPriceIndex);
    myOrders().then(setOrders);
  }, []);
  useFocusEffect(load);
  const c = palette();

  async function buy(offering) {
    try {
      setMessage(null);
      const order = await orderOffering(offering);
      setMessage(`Order placed — ${order.state}. Digital plans activate in seconds.`);
      load();
    } catch (e) {
      setMessage(e.message);
    }
  }

  return (
    <ScrollView contentContainerStyle={{ padding: 14 }}>
      {message && <Card testID="shop-message"><Text style={{ color: c.teal }}>{message}</Text></Card>}
      {orders.length > 0 && (
        <Card title="Your orders">
          {orders.slice(0, 3).map((o) => (
            <Row key={o.id} left={<Dim>{o.description || o.id.slice(0, 8)}</Dim>}
                 right={<Text style={{ color: o.state === 'completed' ? c.ok : c.ink }}>{o.state}</Text>} />
          ))}
        </Card>
      )}
      {items.map((o) => (
        <Card key={o.id} title={o.name} testID="offer-card">
          {o.description ? <Dim>{o.description}</Dim> : null}
          {monthly(o, priceIndex) && <Row left={<Dim>from</Dim>} right={monthly(o, priceIndex)} />}
          {o.isBundle || (o.bundledProductOffering || []).length
            ? <Button ghost label="Configure in storefront"
                onPress={() => Linking.openURL('/shop/offering/' + o.id)} />
            : <Button testID={`buy-${o.name.replace(/\W+/g, '-')}`} label="Get this plan"
                onPress={() => buy(o)} />}
        </Card>
      ))}
    </ScrollView>
  );
}
