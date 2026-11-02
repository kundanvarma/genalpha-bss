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
import { choiceBenefits, choicePreview, myChoices, myLines, pickBenefit, productOfPick, releaseBenefit } from '../choices.js';
import { Button, Card, Dim, Row, palette } from '../ui.js';

export default function ChoicesCard() {
  const [pool, setPool] = useState(null);
  const [shelf, setShelf] = useState([]);
  const [msg, setMsg] = useState(null);
  const [unread, setUnread] = useState(false);
  const [busy, setBusy] = useState(false);
  const [answers, setAnswers] = useState({}); // offer key -> ordering's "can I pick this?"
  const [lines, setLines] = useState([]);     // my numbered lines, for a per-line benefit (#420)
  const [linesUnread, setLinesUnread] = useState(false);
  const round = useRef(0);
  const c = palette();
  const refresh = useCallback(() => {
    const mine = ++round.current;   // a later refresh's answers win; an older round's are dropped
    // my lines are read on their own: if they cannot be, the page says so — per-line benefits wait
    const linesRead = myLines().then((ls) => { setLinesUnread(false); return ls; });
    linesRead.catch(() => setLinesUnread(true));
    Promise.all([myChoices(), choiceBenefits(), linesRead.then((ls) => ls, () => [])]).then(([p, benefits, ls]) => {
      if (mine !== round.current) return undefined;
      setPool(p); setShelf(benefits); setLines(ls); setUnread(false);
      if (!p.offered) return undefined;
      return Promise.all(offersOf(benefits, ls, p.allocations).map((o) => choicePreview(o.benefit.id, o.line?.id)
        .then((a) => [o.key, a]).catch(() => [o.key, null])))
        .then((pairs) => { if (mine === round.current) setAnswers(Object.fromEntries(pairs)); });
    }).catch(() => { if (mine === round.current) setUnread(true); });
  }, []);
  useFocusEffect(refresh);

  if (unread) return <Card title="Choices" testID="app-choices-card"><Dim>Your Choices could not be read just now — try again in a moment.</Dim></Card>;
  if (!pool?.offered) return null;   // this operator does not offer Choices — no card
  const label = pool.label || 'Choices';
  const offered = offersOf(shelf, lines, pool.allocations);
  const numberOf = (lineId) => lines.find((l) => l.id === lineId)?.number;
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
          left={<Text>{a.offeringName}{a.lineId && numberOf(a.lineId) ? ` · ${numberOf(a.lineId)}` : ''}{a.state === 'held' ? ' · activating' : ''}{a.heldUntil ? ` · held until ${a.heldUntil}` : ''}</Text>}
          right={a.state === 'committed' && !a.heldUntil && !busy ? (
            <Button ghost testID="app-choice-release" label="Release"
              onPress={() => act(`${a.offeringName} released`, async () => {
                const product = await productOfPick(a);
                if (!product) throw new Error(`${a.offeringName} is still being set up — try again in a moment`);
                return releaseBenefit(product);
              })} />) : null} />
      ))}
      {msg ? <Dim testID="app-choices-msg">{msg}</Dim> : null}
      {linesUnread && shelf.some((b) => b.choicePerLine)
        ? <Dim testID="app-lines-unread">Your lines could not be read just now, so benefits picked per line are left out.</Dim> : null}
      {pool.left > 0 && !busy && (
        <View>
          {offered.map((o) => {
            const answer = answers[o.key];
            const name = o.line ? `${o.benefit.name} for ${o.line.number}` : o.benefit.name;
            if (answer && !answer.allowed) {
              return <Dim key={o.key} testID="app-choice-reason">{name}: {answer.reason}</Dim>;
            }
            return <Button key={o.key} ghost testID="app-choice-pick" label={`Pick ${name}`}
              onPress={() => act(`${name} picked — activating now`, () => pickBenefit(o.benefit, o.line?.id))} />;
          })}
        </View>
      )}
    </Card>
  );
}

/**
 * What can be offered: a household benefit once, a per-line benefit (#420) once
 * for each of my lines — leaving out what is already in use (shown above).
 */
function offersOf(benefits, lines, allocations) {
  const used = (b, line) => allocations.some((a) => a.offeringId === b.id && (!line || a.lineId === line.id));
  return benefits.flatMap((b) => (b.choicePerLine
    ? lines.filter((l) => !used(b, l)).map((l) => ({ key: `${b.id}|${l.id}`, benefit: b, line: l }))
    : (used(b, null) ? [] : [{ key: b.id, benefit: b, line: null }])));
}
