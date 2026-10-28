import { useCallback, useState } from 'react';
import { Text, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { loyaltyProgram, myLoyalty, enrollLoyalty, redeemLoyaltyData, redeemLoyaltyVoucher } from '../api.js';
import { Button, Card, Dim, Row, palette } from '../ui.js';

/** Loyalty parity with the storefront: opt-in, balance + tier, and points
 * spent as data (instant, on this month's meter) or a discount voucher. */
export default function LoyaltyCard() {
  const [prog, setProg] = useState(null);
  const [me, setMe] = useState(null);
  const [msg, setMsg] = useState(null);
  const c = palette();
  const refresh = useCallback(() => {
    loyaltyProgram().then(setProg);
    myLoyalty().then(setMe);
  }, []);
  useFocusEffect(refresh);
  if (!prog?.enabled) return null; // this operator runs no program — no card
  return (
    <Card title="My points ⭐" testID="app-loyalty-card">
      {!me ? (
        <View>
          <Dim>Earn points on every paid bill — spend them as extra data or discounts.</Dim>
          <Button testID="app-loyalty-join" label="Join for free"
            onPress={() => enrollLoyalty().then(refresh).catch((e) => setMsg(e.message))} />
        </View>
      ) : (
        <View>
          <Row left={<Dim>balance</Dim>}
               right={<Text testID="app-loyalty-points" style={{ color: c.teal, fontWeight: '600' }}>
                 {me.balance} pts · {me.tier}</Text>} />
          {msg ? <Dim testID="app-loyalty-msg">{msg}</Dim> : (
            <View>
              {me.balance >= (prog.pointsPerGb ?? 100) && (
                <Button ghost testID="app-loyalty-redeem"
                  label={`${prog.pointsPerGb ?? 100} pts → 1 GB now`}
                  onPress={() => redeemLoyaltyData(1)
                    .then(() => { setMsg('✓ 1 GB added to this month\'s allowance'); refresh(); })
                    .catch((e) => setMsg(e.message))} />
              )}
              {me.balance >= (prog.pointsPerVoucher ?? 200) && (
                <Button ghost testID="app-loyalty-voucher"
                  label={`${prog.pointsPerVoucher ?? 200} pts → ${prog.voucherPercent ?? 10}% voucher`}
                  onPress={() => redeemLoyaltyVoucher()
                    .then((r) => { setMsg(`✓ Voucher ${r.redeemed?.voucherCode || ''} — yours at checkout`); refresh(); })
                    .catch((e) => setMsg(e.message))} />
              )}
            </View>
          )}
        </View>
      )}
    </Card>
  );
}
