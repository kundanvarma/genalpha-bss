/*
 * The adaptive Home: LOB cards assembled from what THIS customer has.
 * Owns nothing -> cross-sell fills the screen; owns everything -> a
 * household dashboard. Same binary, different lives.
 */
import { useCallback, useState } from 'react';
import { RefreshControl, ScrollView, Text, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { TextInput } from 'react-native';
import { changePlan, forYou, listOfferings, myBills, myParty, myProducts, myRecommendations,
  myServices, mySim, myUsage, openProblems, orgName, priceIndex, quickOrder, resetSimPin,
  myHousehold, acceptDependent, endHouseholdLink, orderForDependent, addFamilyMember,
  memberProducts, setAllowance, familyApprovals, decideApproval, giftData } from '../api.js';
import { tokenClaims } from '../auth.js';
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
  const [personal, setPersonal] = useState(null);
  const [offerings, setOfferings] = useState([]);
  const [prices, setPrices] = useState({});
  const [toppedUp, setToppedUp] = useState(false);
  const [changed, setChanged] = useState(null);
  const [org, setOrg] = useState(null);
  const [household, setHousehold] = useState(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(() => {
    myProducts().then(setProducts);
    myServices().then(setServices);
    myUsage().then(setUsage);
    myBills().then(setBills);
    openProblems().then(setProblems);
    // the individualized rail first (governed caption, self-scoped);
    // raw TMF680 when the intelligence component is absent
    forYou().then((fy) => {
      if (fy && fy.items?.length) {
        setPersonal(fy);
        setRecs(fy.items.map((i) => ({ offering: i })));
      } else {
        myRecommendations().then(setRecs);
      }
    });
    listOfferings().then(setOfferings);
    priceIndex().then(setPrices);
    // B2B member? the company pays — the app recomposes around that
    myParty().then((p) => {
      const orgId = p?.organization?.id;
      if (orgId) orgName(orgId).then((n) => setOrg(n || 'your company'));
    });
    myHousehold().then(setHousehold);
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
          <Dim>Your plan is provided by {org} — company charges go on its invoice. Anything you buy yourself shows on your own bill below.</Dim>
        </View>
      )}
      {household?.payer?.status === 'active' && (
        <View testID="app-household-banner" style={{ borderRadius: 10, padding: 10, marginBottom: 10, borderWidth: 1, borderColor: c.line }}>
          <Dim>Your plan is paid for by {household.payer.name || 'your family'} — it goes on their bill. Anything you buy yourself shows on your own bill.</Dim>
        </View>
      )}

      {products.filter((p) => ((p.relatedParty || [])
          .find((x) => x.role === 'customer')?.id ?? tokenClaims().sub) === tokenClaims().sub)
        .map((p) => {
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
            return toppedUp === 'held'
              ? <Dim testID="app-topup-held">🔔 sent to your family admin for approval — you'll hear the moment they decide</Dim>
              : toppedUp
              ? <Dim>✓ top-up added to this month's allowance</Dim>
              : <Button ghost testID="app-topup"
                  label={`＋ ${topup.name}${price ? ` — ${money(price.price.value, price.price.unit)}` : ''}`}
                  onPress={() => quickOrder(topup).then((o) => {
                    setToppedUp(o.state === 'held' ? 'held' : true);
                    if (o.state !== 'held') setTimeout(load, 4000);
                  })} />;
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

      {openBill && (
        <Card title={`Your bill · ${money(openBill.amountDue.value, openBill.amountDue.unit)}`} testID="app-personal-bill">
          {org ? <Dim>Your personal charges — separate from {org}'s invoice.</Dim> : null}
          <Button label="View & pay" onPress={() => navigation.navigate('Bills')} />
        </Card>
      )}

      {(household?.payer || (household?.dependents || []).length > 0
          || (household?.family || []).length > 0) && (
        <FamilyCard household={household} offerings={offerings} prices={prices}
          onChanged={() => { myHousehold().then(setHousehold); setTimeout(load, 3000); }} />
      )}

      {recs.length > 0 && (
        <Card title={products.length ? 'Complete your home' : 'For you'} testID="recs-card">
          {personal?.caption && (
            <Row testID="app-foryou-caption" left={`✨ ${personal.caption}`} />
          )}
          {personal?.retentionFlag && (
            <Row testID="app-retention-banner"
                 left="💙 Thanks for being with us — loyalty picks included." />
          )}
          {recs.slice(0, 3).map((it) => (
            <Row key={it.offering.id} left={`➕ ${it.offering.name}`}
                 right={<Button ghost label="View" onPress={() => navigation.navigate('Shop')} />} />
          ))}
        </Card>
      )}
    </ScrollView>
  );
}

/** One person, many payers — the pocket edition. A dependent sees who pays
 * for them (and can leave); a payer sees their people, accepts requests,
 * orders a plan straight onto a kid's line, or mints a child account whose
 * credentials show once for hand-over to the kid's phone. A FAMILY ADMIN
 * (promoted by the payer) sees the whole family too — orders bill to the
 * family payer, never to the admin. */
function FamilyCard({ household, offerings, prices, onChanged }) {
  const c = palette();
  const [made, setMade] = useState(null);
  const [note, setNote] = useState(null);
  const [approvals, setApprovals] = useState([]);
  const [budget, setBudget] = useState({});
  const [giftPhone, setGiftPhone] = useState('');
  const me = tokenClaims().sub;
  const isOwner = (household.dependents || []).length > 0;
  // my own dependents as the payer; the payer's family when I am its admin
  const members = isOwner ? household.dependents
    : (household.family || []).filter((d) => d.id !== me);
  const isAdmin = household.myRole === 'admin' && household.payer?.status === 'active';
  const canManage = isOwner || isAdmin;
  const roleName = (r) => (r === 'admin' ? 'family admin' : r === 'child' ? 'child' : 'member');
  const plans = offerings.filter((o) =>
    ((o.category || [])[0] || {}).name === 'Mobile plans' && !o.isBundle);
  const act = (p, ok) => p.then(() => { setNote(ok); onChanged(); loadApprovals(); }).catch((e) => setNote(e.message));
  const loadApprovals = () => { if (canManage) familyApprovals().then(setApprovals); };
  useFocusEffect(useCallback(() => { loadApprovals(); }, [canManage]));

  return (
    <Card title="Family 👪" testID="app-family-card">
      {household.payer && (
        <Row left={<Dim>{household.payer.status === 'active'
            ? `paid for by ${household.payer.name || household.payer.id}`
              + (household.myRole === 'admin' ? ' · you are a family admin' : '')
            : `waiting for ${household.payer.name || 'them'} to accept`}</Dim>}
          right={<Button ghost label="Leave" testID="app-hh-leave"
            onPress={() => act(endHouseholdLink(tokenClaims().sub), 'left the household')} />} />
      )}
      {approvals.map((o) => (
        <View key={o.id} testID={'app-approval-' + o.id}>
          <Row left={<Text style={{ color: c.ink, fontWeight: '600' }}>
              🔔 {(o.productOrderItem || [])[0]?.productOffering?.name || 'a purchase'} <Dim>· {o.description}</Dim></Text>}
            right={<View style={{ flexDirection: 'row', gap: 6 }}>
              <Button label="Approve" testID="app-appr-approve"
                onPress={() => act(decideApproval(o.id, true), 'approved — it bills to the family payer')} />
              <Button ghost label="Decline" testID="app-appr-deny"
                onPress={() => act(decideApproval(o.id, false), 'declined')} />
            </View>} />
        </View>
      ))}
      {members.map((d) => (
        <View key={d.id} testID={'app-hh-dep-' + d.id}>
          <Row left={<Text style={{ color: c.ink, fontWeight: '600' }}>
              {d.givenName} {d.familyName} <Dim>· {roleName(d.role)}</Dim></Text>}
            right={d.status === 'pending'
              ? (isOwner ? <Button ghost label="Accept" testID="app-hh-accept"
                  onPress={() => act(acceptDependent(d.id), 'accepted')} /> : <Dim>pending</Dim>)
              : (isOwner ? <Button ghost label="Stop paying" testID="app-hh-stop"
                  onPress={() => act(endHouseholdLink(d.id), 'stopped paying')} /> : null)} />
          {d.status === 'active' && <MemberLines memberId={d.id} isOwner={isOwner} />}
          {d.status === 'active' && canManage && (
            <Row left={<Dim>💶 top-up allowance{d.topupAllowance != null ? ` — ${d.topupAllowance} EUR/mo` : ''}</Dim>}
              right={<View style={{ flexDirection: 'row', gap: 6, alignItems: 'center' }}>
                <TextInput placeholder="EUR" value={budget[d.id] || ''} inputMode="decimal"
                  testID="app-fam-allowance"
                  onChangeText={(v) => setBudget((x) => ({ ...x, [d.id]: v.replace(/[^0-9.]/g, '') }))}
                  style={{ borderWidth: 1, borderColor: c.line, borderRadius: 8, padding: 5, width: 62, color: c.ink }} />
                <Button ghost label="Set" testID="app-fam-allowance-set"
                  onPress={() => budget[d.id] && act(setAllowance(d.id, Number(budget[d.id])),
                    'allowance set — top-ups inside it bill the family instantly')} />
              </View>} />
          )}
          {d.status === 'active' && (
            <Row left={<Dim>🎁 gift them a gigabyte</Dim>}
              right={<Button ghost label="Gift 1 GB" testID="app-gift-1gb"
                onPress={() => act(giftData({ receiverId: d.id }, 1), 'gifted 1 GB — their meter grew')} />} />
          )}
          {d.status === 'active' && plans.slice(0, 2).map((o) => (
            <Row key={o.id} left={<Dim>order {o.name} for them</Dim>}
              right={<Button ghost label="Order" testID="app-hh-order"
                onPress={() => act(orderForDependent(o, d.id),
                  isOwner ? 'ordered — bills to you' : 'ordered — bills to the family payer')} />} />
          ))}
        </View>
      ))}
      <Row left={<TextInput placeholder="…or gift to a phone number" value={giftPhone}
          testID="app-gift-phone" inputMode="tel"
          onChangeText={(v) => setGiftPhone(v.replace(/[^0-9+ -]/g, ''))}
          style={{ borderWidth: 1, borderColor: c.line, borderRadius: 8, padding: 6, minWidth: 170, color: c.ink }} />}
        right={<Button ghost label="Gift 1 GB" testID="app-gift-phone-send"
          onPress={() => giftPhone.trim() && act(giftData({ receiverPhone: giftPhone.trim() }, 1),
            `gifted 1 GB to ${giftPhone.trim()}`)} />} />
      {!household.payer && (made
        ? <Dim testID="app-hh-credentials">✓ created — they sign in with {made.email} / {made.temporaryPassword} (shown once)</Dim>
        : <AddFamily onMade={(m) => { setMade(m); onChanged(); }} />)}
      {note && <Dim testID="app-hh-note">{note}</Dim>}
    </Card>
  );
}

/** What the family pays for on this member's line — fetched through the
 * household link (inventory verifies it live at the party source). */
function MemberLines({ memberId, isOwner }) {
  const [lines, setLines] = useState([]);
  useFocusEffect(useCallback(() => { memberProducts(memberId).then(setLines); }, [memberId]));
  return lines.map((p) => (
    <Row key={p.id} left={<Dim>{p.name} · {p.status}</Dim>}
      right={<Dim>{isOwner ? 'billed to you' : 'billed to the family payer'}</Dim>} />
  ));
}

function AddFamily({ onMade }) {
  const c = palette();
  const [given, setGiven] = useState('');
  const [email, setEmail] = useState('');
  const [err, setErr] = useState(null);
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6, flexWrap: 'wrap', marginTop: 6 }}>
      <TextInput placeholder="first name" value={given} onChangeText={setGiven}
        testID="app-hh-add-given"
        style={{ borderWidth: 1, borderColor: c.line, borderRadius: 8, padding: 6, minWidth: 90, color: c.ink }} />
      <TextInput placeholder="email" value={email} onChangeText={setEmail}
        testID="app-hh-add-email" autoCapitalize="none"
        style={{ borderWidth: 1, borderColor: c.line, borderRadius: 8, padding: 6, flex: 1, minWidth: 140, color: c.ink }} />
      <Button ghost label="＋ Add family member" testID="app-hh-add"
        onPress={() => addFamilyMember(given.trim(), 'Family', email.trim())
          .then(onMade).catch((e) => setErr(e.message))} />
      {err ? <Dim>{err}</Dim> : null}
    </View>
  );
}

