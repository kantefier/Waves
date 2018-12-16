package com.wavesplatform.sql

import java.util.concurrent.ThreadLocalRandom

import com.wavesplatform.BlockGen
import com.wavesplatform.account.PrivateKeyAccount
import com.wavesplatform.block.Block
import com.wavesplatform.crypto.KeyLength
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.settings.TestFunctionalitySettings
import com.wavesplatform.state.{BinaryDataEntry, BooleanDataEntry, ByteStr, IntegerDataEntry, SqlDb, StringDataEntry}
import com.wavesplatform.transaction.transfer.{TransferTransactionV1, TransferTransactionV2}
import com.wavesplatform.transaction.{DataTransaction, GenesisTransaction}
import monix.execution.Scheduler.fixedPool
import org.scalatest.{FreeSpec, Matchers}

class SqlStorageTest extends FreeSpec with Matchers with BlockGen {

  implicit val ec = fixedPool("miner-pool", poolSize = 20, reporter = println)

  "sql db test" - {
    val db = new SqlDb(TestFunctionalitySettings.Enabled)

    "init succcessful" in {
      println("success")
    }

    "test insert block" in {
      val master, recipient = randomPrivateKeyAccount()
      val block             = getTwoMinersBlockChain(master, recipient, 0).head
      db.insertBlock(block, 1, 5)
    }
  }

  private val signerA, signerB = randomPrivateKeyAccount()

  def randomPrivateKeyAccount(): PrivateKeyAccount = {
    val seed = Array.ofDim[Byte](KeyLength)
    ThreadLocalRandom.current().nextBytes(seed)
    PrivateKeyAccount(seed)
  }

  private def getTwoMinersBlockChain(from: PrivateKeyAccount, to: PrivateKeyAccount, numPayments: Int): Seq[Block] = {
    val ts                   = System.currentTimeMillis() - 100000
    val genesisTx            = GenesisTransaction.create(from, Long.MaxValue - 1, ts).right.get
    val features: Set[Short] = Set[Short](2)

//    val paymentTxs = (1 to numPayments).map { i =>
//      createWavesTransfer(
//        from,
//        to,
//        amount = 10000,
//        0,
//        timestamp = ts + i * 1000
//      ).explicitGet()
//    }

    (genesisTx +: Nil).zipWithIndex.map {
      case (x, i) =>
        val signer = if (i % 2 == 0) signerA else signerB
        TestBlock.create(signer, Seq(x), features)
    }
  }

  "data db test" - {

    val db = new SqlDb(TestFunctionalitySettings.Enabled)

    "db put works" in {
      val des = List(
        IntegerDataEntry("ide", 3),
        BooleanDataEntry("bde", true),
        StringDataEntry("sde", "yolo"),
        BinaryDataEntry("bdes", ByteStr(Array[Byte](4, 2, 0)))
      )

      val tx = DataTransaction.selfSigned(1, signerA, des, 300, System.currentTimeMillis()).right.get
      db.putData(1, tx)
    }

    "transfer db test" in {
      val t1 = TransferTransactionV1
        .selfSigned(None, signerA, signerB.toAddress, 400, System.currentTimeMillis(), None, 300, Array[Byte](4, 2, 0))
        .right
        .get

      val t2 = TransferTransactionV2
        .selfSigned(2, None, signerB, signerA.toAddress, 400, System.currentTimeMillis() + 100, None, 300, Array[Byte](6, 9))
        .right
        .get

      db.insertTransfer(t1, 1)
      db.insertTransfer(t2, 1)

    }
  }

}
