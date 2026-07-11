import { useCallback, useState } from 'react';
import { ScrollView, Text, TextInput } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { createTicket, myNotifications, myTickets, openProblems } from '../api.js';
import { Button, Card, Dim, Row, palette } from '../ui.js';

export default function Help() {
  const [notes, setNotes] = useState([]);
  const [tickets, setTickets] = useState([]);
  const [problems, setProblems] = useState([]);
  const [subject, setSubject] = useState('');
  const c = palette();

  const load = useCallback(() => {
    myNotifications().then(setNotes);
    myTickets().then(setTickets);
    openProblems().then(setProblems);
  }, []);
  useFocusEffect(load);

  async function raise() {
    if (!subject.trim()) return;
    await createTicket(subject.trim(), 'Raised from the app');
    setSubject('');
    load();
  }

  return (
    <ScrollView contentContainerStyle={{ padding: 14 }}>
      {problems.length > 0 && (
        <Card><Text style={{ color: c.err }}>⚠ Known outage: {problems[0].name} — no need to report.</Text></Card>
      )}
      <Card title="Notifications" testID="inbox-card">
        {notes.slice(0, 8).map((n) => (
          <Row key={n.id} left={n.subject} right={<Dim>{(n.sendTime || '').slice(5, 10)}</Dim>} />
        ))}
        {!notes.length && <Dim>Nothing yet.</Dim>}
      </Card>
      <Card title="Your tickets">
        {tickets.map((t) => (
          <Row key={t.id} left={t.name}
               right={<Text style={{ color: t.status === 'resolved' ? c.ok : c.dim }}>{t.status}</Text>} />
        ))}
        <TextInput value={subject} onChangeText={setSubject} placeholder="Describe a problem…"
          style={{ borderWidth: 1, borderColor: c.line, borderRadius: 8, padding: 8, marginTop: 8 }} />
        <Button ghost label="Raise ticket" onPress={raise} />
      </Card>
    </ScrollView>
  );
}
