-- TMF632 birthDate: age becomes REAL data, not an assumption. A payer-created
-- dependent with a birth date is a 'child' only while under 18 — an adult
-- created the same way joins as a plain member.
ALTER TABLE individual ADD COLUMN birth_date DATE;
