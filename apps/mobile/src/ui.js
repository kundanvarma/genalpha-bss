/* Shared RN building blocks, themed from the tenant manifest. */
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { tenantConfig } from './config.js';

export const palette = () => ({
  teal: tenantConfig().brandColor,
  ink: '#1c2326', dim: '#5c6a70', line: '#e2e8e8', card: '#f5f8f8',
  ok: '#2E7D32', err: '#b42318', bg: '#ffffff',
});

export function Card({ title, children, testID }) {
  const c = palette();
  return (
    <View testID={testID} style={[styles.card, { borderColor: c.line, backgroundColor: c.card }]}>
      {title ? <Text style={[styles.cardTitle, { color: c.ink }]}>{title}</Text> : null}
      {children}
    </View>
  );
}

export function Row({ left, right }) {
  return (
    <View style={styles.row}>
      <View style={{ flexShrink: 1 }}>{typeof left === 'string' ? <Text>{left}</Text> : left}</View>
      <View>{typeof right === 'string' ? <Text style={{ color: palette().dim }}>{right}</Text> : right}</View>
    </View>
  );
}

export function Meter({ used, allowed }) {
  const c = palette();
  const over = allowed != null && used > allowed;
  const pct = allowed ? Math.min(100, (used / allowed) * 100) : 0;
  return (
    <View style={[styles.meter, { backgroundColor: c.line }]}>
      <View style={{ width: `${pct}%`, backgroundColor: over ? c.err : c.teal, height: 7, borderRadius: 4 }} />
    </View>
  );
}

export function Button({ label, onPress, ghost, testID }) {
  const c = palette();
  return (
    <Pressable testID={testID} onPress={onPress}
      style={[styles.btn, ghost
        ? { borderColor: c.teal, borderWidth: 1 }
        : { backgroundColor: c.teal }]}>
      <Text style={{ color: ghost ? c.teal : '#fff', fontWeight: '600' }}>{label}</Text>
    </Pressable>
  );
}

export function Dim({ children }) {
  return <Text style={{ color: palette().dim, fontSize: 13 }}>{children}</Text>;
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: 12, padding: 12, marginBottom: 10 },
  cardTitle: { fontWeight: '700', marginBottom: 6, fontSize: 15 },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 5 },
  meter: { height: 7, borderRadius: 4, marginVertical: 6, overflow: 'hidden' },
  btn: { borderRadius: 8, paddingVertical: 8, paddingHorizontal: 14, alignItems: 'center', marginTop: 8, alignSelf: 'flex-start' },
});
