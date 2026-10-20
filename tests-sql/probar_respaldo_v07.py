"""Pruebas SQLite del mecanismo VACUUM INTO, no sustituyen tests JUnit de BackupService Java."""
import sqlite3
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
MIGRATIONS=ROOT/'src/main/resources/db/migration'

class SQLiteBackupTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='respaldo v07 ')
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.src=self.root/'origen.sqlite'
        self.db=sqlite3.connect(self.src)
        self.addCleanup(self.db.close)
        for migration in sorted(MIGRATIONS.glob('V*.sql')):
            self.db.executescript(migration.read_text(encoding='utf-8'))
        self.db.commit()

    def vacuum(self, target):
        # Sintaxis SQL utilizada también por el servicio Java.
        self.db.execute("VACUUM INTO '"+str(target).replace("'", "''")+"'")

    def test_esquema_y_integridad_conservados(self):
        backup=self.root/'respaldo.sqlite';self.vacuum(backup)
        with sqlite3.connect(backup) as copy:
            self.assertEqual('ok',copy.execute('PRAGMA integrity_check').fetchone()[0])
            self.assertEqual([],copy.execute('PRAGMA foreign_key_check').fetchall())
            original=self.db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='table'").fetchone()[0]
            result=copy.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='table'").fetchone()[0]
            self.assertEqual(original,result)

    def test_archivo_con_espacios_y_comillas(self):
        backup=self.root/"respaldo de José 'v07'.sqlite";self.vacuum(backup)
        self.assertTrue(backup.exists())
        with sqlite3.connect(backup) as copy:
            self.assertEqual('ok',copy.execute('PRAGMA integrity_check').fetchone()[0])

    def test_no_copia_transacciones_sin_confirmar(self):
        # Tabla independiente para probar aislamiento de instantáneas.
        self.db.execute('CREATE TABLE prueba (id INTEGER PRIMARY KEY, valor TEXT)')
        self.db.execute("INSERT INTO prueba VALUES (1,'guardado')");self.db.commit()
        other=sqlite3.connect(self.src)
        try:
            other.execute('BEGIN')
            other.execute("INSERT INTO prueba VALUES (2,'no confirmado')")
            target=self.root/'vacuum.sqlite';self.vacuum(target)
            with sqlite3.connect(target) as c:
                self.assertEqual([(1,'guardado')],c.execute('SELECT * FROM prueba').fetchall())
        finally:other.rollback();other.close()

    def test_archivo_de_respaldo_no_cambia_con_nuevos_datos(self):
        self.db.execute('CREATE TABLE prueba (id INTEGER PRIMARY KEY)')
        self.db.execute('INSERT INTO prueba VALUES (1)');self.db.commit()
        backup=self.root/'original.sqlite';self.vacuum(backup)
        self.db.execute('INSERT INTO prueba VALUES (2)');self.db.commit()
        with sqlite3.connect(backup) as copy:
            self.assertEqual(1,copy.execute('SELECT COUNT(*) FROM prueba').fetchone()[0])
        self.assertEqual(2,self.db.execute('SELECT COUNT(*) FROM prueba').fetchone()[0])

if __name__=='__main__':unittest.main(verbosity=2)
