/*
 * The adaptive Home: LOB cards assembled from what THIS customer has.
 * Owns nothing -> cross-sell fills the screen; owns everything -> a
 * household dashboard. Same binary, different lives.
 */
import { useCallback, useState } from 'react';
import { RefreshControl, ScrollView, Text, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { myBills, myProducts, myRecommendations, myServices, myUsage, openProblems } from '../api.js';
import { Button, Card, Dim, Meter, Row, palette } from '../ui.js';

export default function Home({ navigation }) {
  const [products, setProducts] = useState([]);
  const [services, setServices] = useState([]);
  const [usage, setUsage] = useState([]);
  const [bills, setBills] = useState([]);
  const [problems, setProblems] = useState([]);
  const [recs, setRecs] = useState([]);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(() => {
    myProducts().then(setProducts);
    myServices().then(setServices);
    myUsage().then(setUsage);
    myBills().then(setBills);
    openProblems().then(setProblems);
    myRecommendations().then(setRecs);
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

      {products.map((p) => (
        <Card key={p.id} title={p.name} testID="lob-card">
          <Row left={<Dim>status</Dim>}
               right={<Text style={{ color: c.ok }}>{p.status}</Text>} />
          {numberFor(p.name) && (
            <Row left={<Dim>your number</Dim>}
                 right={<Text testID="msisdn" style={{ color: c.teal, fontWeight: '600' }}>{numberFor(p.name)}</Text>} />
          )}
        </Card>
      ))}
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
        <Card title={`Your bill · ${openBill.amountDue.value.toFixed(2)} ${openBill.amountDue.unit}`}>
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
