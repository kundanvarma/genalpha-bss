/*
 * MyGenAlpha / MyNova — the fourth channel. One binary: the tenant manifest
 * decides branding and IdP; the customer's own inventory decides which LOB
 * modules the Home composes. Web today (E2E-verified), native next.
 */
import { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import { NavigationContainer } from '@react-navigation/native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { loadTenantConfig, tenantConfig } from './src/config.js';
import { beginLogin, completeLogin, ensurePartyOnce, isSignedIn } from './src/boot.js';
import Home from './src/screens/Home.js';
import Usage from './src/screens/Usage.js';
import Bills from './src/screens/Bills.js';
import Shop from './src/screens/Shop.js';
import Help from './src/screens/Help.js';

const Tab = createBottomTabNavigator();
const ICONS = { Home: '⌂', Usage: '📶', Bills: '💳', Shop: '🛍', Help: '💬' };

export default function App() {
  const [state, setState] = useState('booting');

  useEffect(() => {
    (async () => {
      await loadTenantConfig();
      if (await completeLogin()) {
        await ensurePartyOnce();
        setState('ready');
      } else {
        setState('signed-out');
      }
    })().catch(() => setState('signed-out'));
  }, []);

  if (state === 'booting') {
    return <Center><Text>Starting {tenantConfig().brandName}…</Text></Center>;
  }
  if (state === 'signed-out') {
    const c = tenantConfig();
    return (
      <Center>
        <Text style={{ fontSize: 26, fontWeight: '700', color: c.brandColor, marginBottom: 8 }}>
          {c.brandName}
        </Text>
        <Text style={{ color: '#5c6a70', marginBottom: 18 }}>Your services, one place.</Text>
        <Text testID="signin" onPress={() => beginLogin()}
          style={{ backgroundColor: c.brandColor, color: '#fff', paddingVertical: 10,
                   paddingHorizontal: 26, borderRadius: 10, fontWeight: '600', overflow: 'hidden' }}>
          Sign in / register
        </Text>
      </Center>
    );
  }

  const brand = tenantConfig();
  return (
    <NavigationContainer>
      <Tab.Navigator screenOptions={({ route }) => ({
        headerTitle: route.name === 'Home' ? brand.brandName : route.name,
        headerTintColor: brand.brandColor,
        headerTitleStyle: { fontWeight: '700' },
        tabBarActiveTintColor: brand.brandColor,
        tabBarIcon: ({ color }) => <Text style={{ color, fontSize: 16 }}>{ICONS[route.name]}</Text>,
      })}>
        <Tab.Screen name="Home" component={Home} />
        <Tab.Screen name="Usage" component={Usage} />
        <Tab.Screen name="Bills" component={Bills} />
        <Tab.Screen name="Shop" component={Shop} />
        <Tab.Screen name="Help" component={Help} />
      </Tab.Navigator>
    </NavigationContainer>
  );
}

function Center({ children }) {
  return (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: '#fff' }}>
      {children}
    </View>
  );
}
