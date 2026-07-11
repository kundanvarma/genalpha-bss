import { useCallback, useState } from 'react';
import { ScrollView, Text } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { billRates, myBills, myMethods, payBill } from '../api.js';
import { Button, Card, Dim, Row, palette } from '../ui.js';

export default function Bills() {
  const [bills, setBills] = useState([]);
  const [methods, setMethods] = useState([]);
  const [lines, setLines] = useState({});
  const [error, setError] = useState(null);

  const load = useCallback(() => {
    myBills().then(setBills);
    myMethods().then(setMethods);
  }, []);
  useFocusEffect(load);
  const c = palette();

  async function showLines(bill) {
    setLines({ ...lines, [bill.id]: await billRates(bill.id) });
  }

  async function pay(bill, method) {
    try {
      setError(null);
      await payBill(bill, method.id);
      load();
    } catch (e) {
      setError(e.message);
    }
  }

  return (
    <ScrollView contentContainerStyle={{ padding: 14 }}>
      {error && <Card><Text style={{ color: c.err }}>{error}</Text></Card>}
      {bills.map((b) => (
        <Card key={b.id} title={`${b.billNo} · ${b.amountDue.value.toFixed(2)} ${b.amountDue.unit}`}>
          <Row left={<Dim>status</Dim>}
               right={<Text style={{ color: b.state === 'settled' ? c.ok : c.ink }}>{b.state}</Text>} />
          {(lines[b.id] || []).map((r) => (
            <Row key={r.id} left={<Dim>{r.name}</Dim>}
                 right={`${r.taxExcludedAmount.value.toFixed(2)}`} />
          ))}
          {!lines[b.id] && <Button ghost label="Line items" onPress={() => showLines(b)} />}
          {b.state !== 'settled' && methods.map((m) => (
            <Button key={m.id} testID="pay-saved"
              label={`Pay with ${m.details.brand} •••• ${m.details.lastFourDigits}`}
              onPress={() => pay(b, m)} />
          ))}
        </Card>
      ))}
      {!bills.length && <Card><Dim>No bills yet — they appear after each billing period.</Dim></Card>}
    </ScrollView>
  );
}
