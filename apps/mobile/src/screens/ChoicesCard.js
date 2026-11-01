/*
 * The household's Choices on the app's Home (#389): how many are left, in the
 * operator's own word, what is in use, and the benefits to pick — the shop's
 * Choices page, as one card. Nothing when the operator does not offer Choices;
 * a sentence, not an empty card, when the ledger cannot be read. Whether a
 * benefit can be picked is ordering's answer (choicePreview), never the app's
 * own arithmetic; an unanswered preview leaves it to the order, the authority.
 */
import { useCallback, useRef, useState } from 'react';
import { Text, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { choiceBenefits, choicePreview, myChoices, pickBenefit, productOfPick, releaseBenefit } from '../choices.js';
import { Button, Card, Dim, Row, palette } from '../ui.js';

export default function ChoicesCard() {
  const [pool, setPool] = useState(null);
  const [shelf, setShelf] = useState([]);
  const [msg, setMsg] = useState(null);
  const [unread, setUnread] = useState(false);
  const [busy, setBusy] = useState(false);
  const [answers, setAnswers] = useState({}); // offeringId -> ordering's "can I pick this?"
  const round = useRef(0);
  const c = palette();
  const refresh = useCallback(() => {
    const mine = ++round.current;   // a later refresh's answers win; an older round's are dropped
    Promise.all([myChoices(), choiceBenefits()]).then(([p, benefits]) => {
      if (mine !== round.current) return undefined;
      setPool(p); setShelf(benefits); setUnread(false);
      if (!p.offered) return undefined;
      return Promise.all(benefits.map((b) => choicePreview(b.id).then((a) => [b.id, a]).catch(() => [b.id, null])))
        .then((pairs) => { if (mine === round.current) setAnswers(Object.fromEntries(pairs)); });
    }).catch(() => { if (mine === round.current) setUnread(true); });
  }, []);
  useFocusEffect(refresh);

  if (unread) return <Card title="Choices" testID="app-choices-card"><Dim>Your Choices could not be read just now — try again in a moment.</Dim></Card>;
  if (!pool?.offered) return null;   // this operator does not offer Choices — no card
  const label = pool.label || 'Choices';
  const inUse = new Set(pool.allocations.map((a) => a.offeringId));   // shown above, not offered again
  const offered = shelf.filter((b) => !inUse.has(b.id));
  const act = (what, fn) => {
    setBusy(true); setMsg(null);
    fn().then(() => { setMsg(what); refresh(); }).catch((e) => setMsg(e.message)).finally(() => setBusy(false));
  };

  return (
    <Card title={label} testID="app-choices-card">
      <Row left={<Dim>left to pick</Dim>}
        right={<Text testID="app-choices-left" style={{ color: c.teal, fontWeight: '600' }}>{pool.left} of {pool.granted} {label}</Text>} />
      {pool.allocations.map((a) => (
        <Row key={a.id} testID="app-choice-row"
          left={<Text>{a.offeringName}{a.state === 'held' ? ' · activating' : ''}{a.heldUntil ? ` · held until ${a.heldUntil}` : ''}</Text>}
          right={a.state === 'committed' && !a.heldUntil && !busy ? (
            <Button ghost testID="app-choice-release" label="Release"
              onPress={() => act(`${a.offeringName} released`, async () => {
                const product = await productOfPick(a);
                if (!product) throw new Error(`${a.offeringName} is still being set up — try again in a moment`);
                return releaseBenefit(product);
              })} />) : null} />
      ))}
      {msg ? <Dim testID="app-choices-msg">{msg}</Dim> : null}
      {pool.left > 0 && !busy && (
        <View>
          {offered.map((b) => {
            const answer = answers[b.id];
            if (answer && !answer.allowed) {
              return <Dim key={b.id} testID="app-choice-reason">{b.name}: {answer.reason}</Dim>;
            }
            return <Button key={b.id} ghost testID="app-choice-pick" label={`Pick ${b.name}`}
              onPress={() => act(`${b.name} picked — activating now`, () => pickBenefit(b))} />;
          })}
        </View>
      )}
    </Card>
  );
}
