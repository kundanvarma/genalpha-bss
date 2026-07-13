/*
 * The adaptive Home: LOB cards assembled from what THIS customer has.
 * Owns nothing -> cross-sell fills the screen; owns everything -> a
 * household dashboard. Same binary, different lives.
 */
import { useCallback, useState } from 'react';
import { RefreshControl, ScrollView, Text, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { TextInput } from 'react-native';
import { changePlan, listOfferings, myBills, myParty, myProducts, myRecommendations,
  myServices, mySim, myUsage, openProblems, orgName, priceIndex, quickOrder, resetSimPin } from '../api.js';
import { money } from '../config.js';
import { Button, Card, Dim, Meter, Row, palette } from '../ui.js';

/** SIM self-care inline: masked ICCID, PUK on request, OTA PIN reset. */
function SimRow({ serviceId }) {
  const [sim, setSim] = useState(null);
  const [puk, setPuk] = useState(null);
  const [pin, setPin] = useState('');
  const [done, setDone] = useState(false);
  const c = palette();
  useFocusEffect(useCallback(() => { mySim(serviceId).then(setSim); }, [serviceId]));
  if (!sim) return null;
  return (
    <View testID="sim-row">
      <Row left={<Dim>SIM {sim.iccid}</Dim>}
           right={puk
             ? <Text testID="app-puk" style={{ color: c.teal, fontWeight: '600' }}>PUK {puk}</Text>
             : <Button ghost label="Show PUK" testID="app-show-puk"
                 onPress={() => mySim(serviceId, true).then((full) => setPuk(full?.puk))} />} />
      <Row left={<TextInput placeholder="New PIN" value={pin} maxLength={8}
                   onChangeText={(v) => setPin(v.replace(/\D/g, ''))}
                   style={{ borderWidth: 1, borderColor: c.line, borderRadius: 8,
                     paddingHorizontal: 8, paddingVertical: 4, minWidth: 90, color: c.text }} />}
           right={done ? <Dim>✓ sent to your SIM</Dim>
             : <Button ghost label="Reset PIN"
                 onPress={() => pin.length >= 4 && resetSimPin(serviceId, pin).then(() => { setDone(true); setPin(''); })} />} />
    </View>
  );
}

const PLAN_CATS = ['Mobile plans', 'Broadband'];
const catOf = (o) => ((o?.category || [])[0] || {}).name || '';

/** Like-for-like plan change: tap Change, tap the new plan — same number. */
function PlanChange({ product, service, offerings, prices, onChanged }) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const c = palette();
  const current = offerings.find((o) => o.id === product.productOffering?.id);
  if (!current || !PLAN_CATS.includes(catOf(current)) || product.status !== 'active') return null;
  const options = offerings.filter((o) => !o.isBundle && !o.requiresVerifiedIdentity
    && o.id !== current.id && catOf(o) === catOf(current))
    .map((o) => {
      const pr = (o.productOfferingPrice || []).map((r) => prices[r.id])
        .find((p) => p?.priceType === 'recurring' && p.price?.value != null);
      return pr ? { ...o, priceLabel: `${money(pr.price.value, pr.price.unit)}/mo` } : null;
    }).filter(Boolean);
  if (!options.length) return null;
  if (!open) {
    return <Button ghost testID="app-change-plan" label="Change plan — keep your number"
      onPress={() => setOpen(true)} />;
  }
  return (
    <View testID="app-plan-options">
      {options.map((o) => (
        <Row key={o.id} left={o.name}
             right={<Button ghost testID={`app-pick-${o.id}`} label={busy ? '…' : o.priceLabel}
               onPress={() => {
                 if (busy) return;
                 setBusy(true);
                 changePlan(product.id, service?.id, o)
                   .then(() => { setOpen(false); onChanged(o.name); })
                   .finally(() => setBusy(false));
               }} />} />
      ))}
      <Button ghost label="Cancel" onPress={() => setOpen(false)} />
    </View>
  );
}

export default function Home({ navigation }) {
  const [products, setProducts] = useState([]);
  const [services, setServices] = useState([]);
  const [usage, setUsage] = useState([]);
  const [bills, setBills] = useState([]);
  const [problems, setProblems] = useState([]);
  const [recs, setRecs] = useState([]);
  const [offerings, setOfferings] = useState([]);
  const [prices, setPrices] = useState({});
  const [toppedUp, setToppedUp] = useState(false);
  const [changed, setChanged] = useState(null);
  const [org, setOrg] = useState(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(() => {
    myProducts().then(setProducts);
    myServices().then(setServices);
    myUsage().then(setUsage);
    myBills().then(setBills);
    openProblems().then(setProblems);
    myRecommendations().then(setRecs);
    listOfferings().then(setOfferings);
    priceIndex().then(setPrices);
    // B2B member? the company pays — the app recomposes around that
    myParty().then((p) => {
      const orgId = p?.organization?.id;
      if (orgId) orgName(orgId).then((n) => setOrg(n || 'your company'));
    });
  }, []);
  useFocusEffect(load);

  const c = palette();
  const numberFor = (name) => services.find((s) => s.name === name
    && (s.supportingResource || []).length)?.supportingResource[0].value;
  const openBill = bills.find((b) => b.state !== 'settled');

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={{ padding: 14 }}
      refreshControl={<RefreshControl refreshing={refreshing}
        onRefresh={() => { setRefreshing(true); load(); setTimeout(() => setRefreshing(false), 600); }} />}>

      {problems.length > 0 && (
        <View testID="outage" style={{ backgroundColor: c.err, borderRadius: 10, padding: 10, marginBottom: 10 }}>
          <Text style={{ color: '#fff' }}>⚠ {problems[0].name} — we're on it.</Text>
        </View>
      )}

      {changed && (
        <View testID="app-plan-changed" style={{ backgroundColor: c.teal, borderRadius: 10, padding: 10, marginBottom: 10 }}>
          <Text style={{ color: '#fff' }}>✓ Plan changed to {changed} — you keep your number.</Text>
        </View>
      )}
      {org && (
        <View testID="app-org-banner" style={{ borderRadius: 10, padding: 10, marginBottom: 10, borderWidth: 1, borderColor: c.line }}>
          <Dim>Your plan is provided by {org} — charges go on the company's invoice.</Dim>
        </View>
      )}

      {products.map((p) => {
        const svc = services.find((s) => s.name === p.name);
        const code = (svc?.serviceCharacteristic || []).find((ch) => ch.name === 'activationCode')?.value;
        return (
        <Card key={p.id} title={p.name} testID="lob-card">
          <Row left={<Dim>status</Dim>}
               right={<Text style={{ color: c.ok }}>{p.status}</Text>} />
          {numberFor(p.name) && (
            <Row left={<Dim>your number</Dim>}
                 right={<Text testID="msisdn" style={{ color: c.teal, fontWeight: '600' }}>{numberFor(p.name)}</Text>} />
          )}
          <PlanChange product={p} service={svc} offerings={offerings} prices={prices}
            onChanged={(name) => { setChanged(name); setTimeout(load, 2500); }} />
          {numberFor(p.name) && svc && <SimRow serviceId={svc.id} />}
          {numberFor(p.name) && (() => {
            const topup = offerings.find((o) =>
              ((o.category || [])[0] || {}).name === 'Top-ups');
            if (!topup) return null;
            const price = (topup.productOfferingPrice || []).map((r) => prices[r.id])
              .find((pr) => pr?.price?.value != null);
            return toppedUp
              ? <Dim>✓ top-up added to this month's allowance</Dim>
              : <Button ghost testID="app-topup"
                  label={`＋ ${topup.name}${price ? ` — ${money(price.price.value, price.price.unit)}` : ''}`}
                  onPress={() => quickOrder(topup).then(() => { setToppedUp(true); setTimeout(load, 4000); })} />;
          })()}
          {code && (
            <Row left={<Dim>activation code — manage with the partner</Dim>}
                 right={<Text testID="app-entitlement" style={{ color: c.teal, fontWeight: '600' }}>{code}</Text>} />
          )}
        </Card>
        );
      })}
      {!products.length && (
        <Card title="Welcome 👋" testID="empty-home">
          <Dim>Nothing active yet — pick your first plan below or in Shop.</Dim>
        </Card>
      )}

      {usage.length > 0 && (
        <Card title="Usage this month" testID="usage-card">
          {usage.map((b, i) => (
            <View key={i}>
              <Row left={b.name}
                   right={<Text style={{ color: b.allowedValue != null && Number(b.usedValue) > Number(b.allowedValue) ? c.err : c.dim }}>
                     {b.usedValue}{b.allowedValue != null ? ` / ${b.allowedValue}` : ''} {b.units}</Text>} />
              <Meter used={Number(b.usedValue)} allowed={b.allowedValue == null ? null : Number(b.allowedValue)} />
            </View>
          ))}
        </Card>
      )}

      {openBill && !org && (
        <Card title={`Your bill · ${money(openBill.amountDue.value, openBill.amountDue.unit)}`}>
          <Button label="View & pay" onPress={() => navigation.navigate('Bills')} />
        </Card>
      )}

      {recs.length > 0 && (
        <Card title={products.length ? 'Complete your home' : 'For you'} testID="recs-card">
          {recs.slice(0, 3).map((it) => (
            <Row key={it.offering.id} left={`➕ ${it.offering.name}`}
                 right={<Button ghost label="View" onPress={() => navigation.navigate('Shop')} />} />
          ))}
        </Card>
      )}
    </ScrollView>
  );
}
