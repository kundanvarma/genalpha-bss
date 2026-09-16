/*
 * The buy tab: browse the tenant's catalog; plan-only offerings order in
 * one tap and the SOM completes them in seconds — the app shows the whole
 * loop. Bundles/devices point to the full storefront configurator for now.
 */
import { useCallback, useEffect, useState } from 'react';
import { Image, Linking, Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { checkConfiguration, myOrders, offerings, orderOffering, prices, queryConfiguration } from '../api.js';
import { Button, Card, Dim, Row, palette } from '../ui.js';

function monthly(offering, priceIndex) {
  const total = (offering.productOfferingPrice || [])
    .map((ref) => priceIndex[ref.id])
    .filter((p) => p && p.priceType === 'recurring')
    .reduce((sum, p) => sum + Number(p.price.value), 0);
  return total > 0 ? total.toFixed(2) + ' EUR/mo' : null;
}

/* A configurable offering, configured in the app the way the shop and the desk do it: the oracle says which
 * values may be picked and what the picks cost; the app renders that and orders with the picks. */
function Configure({ offering, onOrder, c, fallback }) {
  const [space, setSpace] = useState(null);
  const [picks, setPicks] = useState({});
  const [qty, setQty] = useState(1);
  const [verdict, setVerdict] = useState(null);
  useEffect(() => {
    queryConfiguration(offering.id).then((sp) => {
      setSpace(sp);
      const d = {};
      for (const ch of sp?.configurationCharacteristic || []) {
        const vals = ch.productSpecCharacteristicValue || [];
        const pick = vals.find((v) => v.isDefault) || vals.find((v) => v.value != null && v.isSelectable !== false);
        if (pick && pick.value != null) d[ch.name] = pick.value;
        const range = vals.find((v) => v.value == null && (v.valueFrom != null || v.valueTo != null));
        if (range) d[ch.name] = String(range.valueFrom ?? 0);
      }
      setPicks(d);
    });
  }, [offering.id]);
  useEffect(() => {
    if (!space) return;
    let live = true;
    checkConfiguration(offering.id, picks, qty).then((v) => { if (live) setVerdict(v); });
    return () => { live = false; };
  }, [space, JSON.stringify(picks), qty]);
  if (!space || (!(space.configurationCharacteristic || []).length && !space.fungible)) return fallback || null;
  const price = verdict?.configurationPrice;
  return (
    <View testID="configure" style={{ marginTop: 6 }}>
      {(space.configurationCharacteristic || []).map((ch) => {
        const vals = ch.productSpecCharacteristicValue || [];
        const range = vals.find((v) => v.value == null && (v.valueFrom != null || v.valueTo != null));
        return (
          <View key={ch.name} style={{ marginBottom: 6 }}>
            <Dim>{ch.name}</Dim>
            {range ? (
              <TextInput keyboardType="numeric" testID={`cfg-${ch.name}`} value={String(picks[ch.name] ?? '')}
                onChangeText={(v) => setPicks((p) => ({ ...p, [ch.name]: v }))}
                style={{ borderWidth: 1, borderColor: c.line, borderRadius: 8, padding: 6, width: 90 }} />
            ) : (
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6 }}>
                {vals.map((v) => (
                  <Pressable key={v.value} disabled={v.isSelectable === false} testID={`cfg-${ch.name}-${v.value}`}
                    onPress={() => setPicks((p) => ({ ...p, [ch.name]: v.value }))}
                    style={{ paddingVertical: 6, paddingHorizontal: 10, borderRadius: 999, borderWidth: 1,
                      borderColor: picks[ch.name] === v.value ? c.teal : c.line, opacity: v.isSelectable === false ? 0.4 : 1 }}>
                    <Text style={{ color: c.ink }}>{v.value}{v.isSelectable === false ? ' · sold out' : ''}</Text>
                  </Pressable>
                ))}
              </View>
            )}
          </View>
        );
      })}
      {space.fungible && (
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 6 }}>
          <Dim>How many</Dim>
          <Pressable onPress={() => setQty((q) => Math.max(1, q - 1))} style={{ padding: 6 }}><Text style={{ color: c.teal, fontSize: 18 }}>−</Text></Pressable>
          <Text testID="cfg-quantity" style={{ color: c.ink }}>{qty}</Text>
          <Pressable onPress={() => setQty((q) => q + 1)} style={{ padding: 6 }}><Text style={{ color: c.teal, fontSize: 18 }}>+</Text></Pressable>
        </View>
      )}
      {verdict?.state === 'accepted' && price && (
        <Text testID="cfg-price" style={{ color: c.ink, fontWeight: '600' }}>
          {Number(price.monthlyTotal?.value || 0).toFixed(2)} {price.monthlyTotal?.unit}/mo
          {Number(price.oneTimeTotal?.value || 0) > 0 ? ` + ${Number(price.oneTimeTotal.value).toFixed(2)} once` : ''}
        </Text>
      )}
      {verdict?.state === 'rejected' && <Text testID="cfg-rejected" style={{ color: c.danger || '#b64a3a' }}>{(verdict.message || []).join(' · ')}</Text>}
      {verdict?.state === 'accepted'
        ? <Button testID="cfg-order" label={space.fungible && qty > 1 ? `Order ×${qty}` : 'Order this configuration'} onPress={() => onOrder(picks, space.fungible ? qty : 1)} />
        : <Button ghost testID="cfg-order" label="Pick a valid configuration" onPress={() => {}} />}
    </View>
  );
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

  async function buy(offering, characteristics = null, quantity = 1) {
    try {
      setMessage(null);
      const order = await orderOffering(offering, characteristics, quantity);
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
          {o.attachment?.[0]?.url && (
            <Image source={{ uri: o.attachment[0].url }}
                   style={{ width: '100%', height: 110, borderRadius: 10, marginBottom: 6 }}
                   resizeMode="cover" />
          )}
          {o.description ? <Dim>{o.description}</Dim> : null}
          {monthly(o, priceIndex) && <Row left={<Dim>from</Dim>} right={monthly(o, priceIndex)} />}
          {o.isBundle || (o.bundledProductOffering || []).length
            ? <Button ghost label="Configure in storefront"
                onPress={() => Linking.openURL('/shop/offering/' + o.id)} />
            : <Configure offering={o} c={c} onOrder={(picks, qty) => buy(o, picks, qty)}
                fallback={<Button testID={`buy-${o.name.replace(/\W+/g, '-')}`} label="Get this plan" onPress={() => buy(o)} />} />}
        </Card>
      ))}
    </ScrollView>
  );
}
