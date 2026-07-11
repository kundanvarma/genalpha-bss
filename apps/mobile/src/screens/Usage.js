import { useCallback, useState } from 'react';
import { ScrollView, Text } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { myUsage } from '../api.js';
import { Card, Dim, Meter, Row, palette } from '../ui.js';

export default function Usage() {
  const [buckets, setBuckets] = useState([]);
  useFocusEffect(useCallback(() => { myUsage().then(setBuckets); }, []));
  const c = palette();
  return (
    <ScrollView contentContainerStyle={{ padding: 14 }}>
      {buckets.map((b, i) => (
        <Card key={i} title={b.name}>
          <Meter used={Number(b.usedValue)} allowed={b.allowedValue == null ? null : Number(b.allowedValue)} />
          <Row left={<Text style={{ color: b.allowedValue != null && Number(b.usedValue) > Number(b.allowedValue) ? c.err : c.ink }}>
              {b.usedValue}{b.allowedValue != null ? ` / ${b.allowedValue}` : ''} {b.units}</Text>}
            right={b.allowedValue != null && Number(b.usedValue) > Number(b.allowedValue)
              ? <Text style={{ color: c.err }}>over allowance</Text> : null} />
        </Card>
      ))}
      {!buckets.length && <Card><Dim>No metered usage yet this month.</Dim></Card>}
    </ScrollView>
  );
}
