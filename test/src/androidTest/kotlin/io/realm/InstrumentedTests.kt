/*
 * Copyright 2020 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.realm.internal.RealmInitializer
import io.realm.util.PlatformUtils
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import test.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class InstrumentedTests {

    val context = InstrumentationRegistry.getInstrumentation().context
    lateinit var tmpDir: String
    lateinit var realm: Realm

    @Before
    fun setup() {
        tmpDir = PlatformUtils.createTempDir()
        val configuration = RealmConfiguration(path = "$tmpDir/default.realm", schema = setOf(Sample::class))
        realm = Realm.open(configuration)
    }

    @After
    fun tearDown() {
        PlatformUtils.deleteTempDir(tmpDir)
    }

    // Smoke test of compiling with library
    @Test
    fun contextIsNotNull() {
        assertNotNull(RealmInitializer.filesDir)
    }

    // This could be a common test, but included here for convenience as there is no other easy
    // way to trigger individual common test on Android
    // https://youtrack.jetbrains.com/issue/KT-34535
    @Test
    fun createAndUpdate() {
        val s = "Hello, World!"
        realm.writeBlocking {
            val sample = create(Sample::class)
            assertEquals("", sample.stringField)
            sample.stringField = s
            assertEquals(s, sample.stringField)
        }
    }

    @Test
    fun query() {
        val s = "Hello, World!"

        realm.writeBlocking {
            create(Sample::class).run { stringField = s }
            create(Sample::class).run { stringField = "Hello, Realm!" }
        }

        val objects1: RealmResults<Sample> = realm.objects(Sample::class)
        assertEquals(2, objects1.size)

        val objects2: RealmResults<Sample> = realm.objects(Sample::class).query("stringField == $0", s)
        assertEquals(1, objects2.size)
        for (sample in objects2) {
            assertEquals(s, sample.stringField)
        }
    }

    @Test
    fun query_parseErrorThrows() {
        val objects3: RealmResults<Sample> = realm.objects(Sample::class).query("name == str")
        // Will first fail when accessing the actual elements as the query is lazily evaluated
        // FIXME Need appropriate error for syntax errors. Avoid UnsupportedOperationException as
        //  in realm-java ;)
        //  https://github.com/realm/realm-kotlin/issues/70
        assertFailsWith<RuntimeException> {
            println(objects3)
        }
    }

    @Test
    fun query_delete() {
        realm.writeBlocking {
            create(Sample::class).run { stringField = "Hello, World!" }
            create(Sample::class).run { stringField = "Hello, Realm!" }
        }

        val objects1: RealmResults<Sample> = realm.objects(Sample::class)
        assertEquals(2, objects1.size)

        realm.writeBlocking {
            realm.objects(Sample::class).delete()
        }

        assertEquals(0, realm.objects(Sample::class).size)
    }

    @Test
    fun delete() {
        realm.writeBlocking {
            val sample = create(Sample::class)
            delete(sample)
            assertFailsWith<IllegalArgumentException> {
                delete(sample)
            }
            assertFailsWith<IllegalStateException> {
                sample.stringField = "sadf"
            }
        }
    }
}
