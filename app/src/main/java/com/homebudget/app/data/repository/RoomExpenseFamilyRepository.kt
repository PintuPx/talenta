package com.homebudget.app.data.repository

import androidx.room.withTransaction
import com.homebudget.app.data.db.AppDatabase
import com.homebudget.app.data.db.FamilyPersonEntity
import com.homebudget.app.data.db.FamilyTagEntity
import com.homebudget.app.data.db.FamilyTagMemberEntity
import com.homebudget.app.data.db.TransactionMemberEntity
import com.homebudget.app.domain.family.ExpenseFamilyRepository
import com.homebudget.app.domain.family.ExpensePerson
import com.homebudget.app.domain.family.ExpensePersonTag
import com.homebudget.app.domain.family.ExpenseShare
import com.homebudget.app.domain.family.ExpenseTag
import com.homebudget.app.domain.family.FamilyReportFilter
import com.homebudget.app.domain.family.ReportAllocation
import com.homebudget.app.domain.family.equalShares
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
// REVIEW: historical member shares and family tags are versioned financial metadata.
class RoomExpenseFamilyRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
    private val writer: VersionedWriter,
) : ExpenseFamilyRepository {
    private val dao get() = db.familyAllocationDao()

    override fun observeActivePeople(): Flow<List<ExpensePerson>> =
        dao.observeActivePeople().map { rows -> rows.map { ExpensePerson(it.uid, it.name, it.active) } }

    override fun observeAllPeople(): Flow<List<ExpensePerson>> =
        dao.observeAllPeople().map { rows -> rows.map { ExpensePerson(it.uid, it.name, it.active) } }

    override fun observeActiveTags(): Flow<List<ExpenseTag>> =
        dao.observeActiveTags().map { rows -> rows.map { ExpenseTag(it.uid, it.name, it.active) } }

    override fun observeTagLinks(): Flow<List<ExpensePersonTag>> =
        dao.observeTagMembers().map { rows -> rows.map { ExpensePersonTag(it.tagUid, it.personUid) } }

    override suspend fun addPerson(name: String): String = db.withTransaction {
        val clean = normalizeName(name, "member")
        if (dao.getPeople().any { normalized(it.name) == normalized(clean) }) {
            throw IntegrityException("A member with that name already exists.")
        }
        val now = clock.millis()
        val person = FamilyPersonEntity(name = clean, active = true, createdAt = now, updatedAt = now)
        writer.apply(
            writer.change("family_person", person.uid, false, "ADD", person) {
                dao.insertPerson(person)
            },
        ).requireAccepted()
        person.uid
    }

    override suspend fun deactivatePerson(uid: String) = db.withTransaction {
        val existing = dao.getPerson(uid) ?: throw IntegrityException("Family member not found.")
        if (!existing.active) return@withTransaction
        val updated = existing.copy(active = false, updatedAt = clock.millis())
        writer.apply(
            writer.change("family_person", uid, false, "EDIT", updated) { dao.updatePerson(updated) },
        ).requireAccepted()
    }

    override suspend fun addTag(name: String): String = db.withTransaction {
        val clean = normalizeName(name, "tag")
        if (clean.equals("All", ignoreCase = true)) throw IntegrityException("\"All\" is a built-in tag.")
        if (dao.getTags().any { normalized(it.name) == normalized(clean) }) {
            throw IntegrityException("A tag with that name already exists.")
        }
        val now = clock.millis()
        val tag = FamilyTagEntity(name = clean, active = true, createdAt = now, updatedAt = now)
        writer.apply(writer.change("family_tag", tag.uid, false, "ADD", tag) {
            dao.insertTag(tag)
        }).requireAccepted()
        tag.uid
    }

    override suspend fun deactivateTag(uid: String) = db.withTransaction {
        val existing = dao.getTag(uid) ?: throw IntegrityException("Tag not found.")
        if (!existing.active) return@withTransaction
        val updated = existing.copy(active = false, updatedAt = clock.millis())
        writer.apply(
            writer.change("family_tag", uid, false, "EDIT", updated) { dao.updateTag(updated) },
        ).requireAccepted()
    }

    override suspend fun setTagMember(tagUid: String, personUid: String, assigned: Boolean) = db.withTransaction {
        val tag = dao.getTag(tagUid)?.takeIf { it.active } ?: throw IntegrityException("Tag not found.")
        val person = dao.getPerson(personUid)?.takeIf { it.active } ?: throw IntegrityException("Family member not found.")
        val existing = dao.getTagMember(tag.uid, person.uid)
        if (assigned && existing != null || !assigned && existing == null) return@withTransaction
        if (assigned) {
            val link = FamilyTagMemberEntity(
                tagUid = tag.uid,
                personUid = person.uid,
                createdAt = clock.millis(),
            )
            writer.apply(writer.change("family_tag_member", link.uid, false, "ADD", link) {
                dao.insertTagMember(link)
            }).requireAccepted()
        } else {
            val link = requireNotNull(existing)
            writer.apply(
                writer.change("family_tag_member", link.uid, false, "DELETE", link, deleted = true) {
                    dao.deleteTagMember(link.id)
                },
            ).requireAccepted()
        }
    }

    override suspend fun transactionShares(transactionUid: String, privateSpace: Boolean): List<ExpenseShare> =
        dao.getTransactionMembersInSpace(transactionUid, privateSpace).map { ExpenseShare(it.personUid, it.shareAmount) }

    override suspend fun saveTransactionShares(
        transactionUid: String,
        totalMinor: Long,
        personUids: Set<String>,
        privateSpace: Boolean,
        month: Int,
    ) = db.withTransaction {
        if (transactionUid.isBlank() || totalMinor < 0) throw IntegrityException("Transaction members are invalid.")
        val allPeople = dao.getPeople()
        val existing = dao.getTransactionMembers(transactionUid)
        val previouslyAssigned = existing.mapTo(HashSet()) { it.personUid }
        val selectableIds = allPeople.filter { it.active || it.uid in previouslyAssigned }.map { it.uid }.toSet()
        if (!selectableIds.containsAll(personUids)) {
            throw IntegrityException("Choose active family members.")
        }
        val selected = allPeople
            .filter { it.uid in personUids }
            .sortedWith(compareBy<FamilyPersonEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.uid })
        val amounts = equalShares(totalMinor, selected.size)
        val amountByPerson = selected.mapIndexed { index, person -> person.uid to amounts[index] }.toMap()
        existing.forEach { old ->
            if (old.personUid !in amountByPerson || old.isPrivate != privateSpace) {
                writer.apply(
                    writer.change(
                        "transaction_member", old.uid, old.isPrivate, "DELETE", old,
                        deleted = true,
                        month = month,
                    ) { dao.deleteTransactionMember(old.id) },
                ).requireAccepted()
            }
        }
        selected.forEach { person ->
            val old = existing.firstOrNull { it.personUid == person.uid && it.isPrivate == privateSpace }
            val amount = amountByPerson.getValue(person.uid)
            if (old == null) {
                val share = TransactionMemberEntity(
                    transactionUid = transactionUid,
                    personUid = person.uid,
                    shareAmount = amount,
                    isPrivate = privateSpace,
                )
                writer.apply(
                    writer.change("transaction_member", share.uid, privateSpace, "ADD", share, month = month) {
                        dao.insertTransactionMember(share)
                    },
                ).requireAccepted()
            } else if (old.shareAmount != amount) {
                val updated = old.copy(shareAmount = amount)
                writer.apply(
                    writer.change("transaction_member", old.uid, privateSpace, "EDIT", updated, month = month) {
                        dao.updateTransactionMember(updated)
                    },
                ).requireAccepted()
            }
        }
    }

    override fun observeReportAllocations(
        filter: FamilyReportFilter,
        privateSpace: Boolean,
    ): Flow<List<ReportAllocation>> = when (filter) {
        FamilyReportFilter.None -> kotlinx.coroutines.flow.flowOf(emptyList())
        FamilyReportFilter.Unassigned -> dao.observeUnassignedReportAllocations(privateSpace)
        is FamilyReportFilter.Person -> dao.observePersonReportAllocations(filter.uid, privateSpace)
        is FamilyReportFilter.Tag -> dao.observeTagReportAllocations(filter.uid, privateSpace)
    }.map { rows -> rows.map { ReportAllocation(it.transactionId, it.shareAmount) } }

    private fun normalizeName(name: String, label: String): String {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank() || clean.length > 50) throw IntegrityException("Enter a $label name (up to 50 characters).")
        return clean
    }

    private fun normalized(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase()
}
