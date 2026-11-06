"""Add bank_accounts, transactions, category_rules, and budgets tables.

Revision ID: 0002_finance_and_ml
Revises: 0001_initial_foundation
Create Date: 2026-10-27 22:25:00+00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0002_finance_and_ml"
down_revision: str | None = "0001_initial_foundation"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "bank_accounts",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.String(length=120), nullable=False),
        sa.Column("institution", sa.String(length=120), nullable=False),
        sa.Column("account_type", sa.String(length=32), nullable=False, server_default="checking"),
        sa.Column("currency", sa.String(length=3), nullable=False, server_default="INR"),
        sa.Column("balance_minor", sa.BigInteger(), nullable=False, server_default="0"),
        sa.Column("masked_number", sa.Text(), nullable=True),
        sa.Column("provider", sa.String(length=32), nullable=False, server_default="mock_aa"),
        sa.Column("external_id", sa.String(length=128), nullable=True),
        sa.Column("last_synced_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_bank_accounts_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_bank_accounts")),
    )
    op.create_index(op.f("ix_bank_accounts_user_id"), "bank_accounts", ["user_id"], unique=False)
    op.create_index(
        op.f("ix_bank_accounts_external_id"), "bank_accounts", ["external_id"], unique=False
    )

    op.create_table(
        "transactions",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("account_id", sa.Uuid(), nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("direction", sa.String(length=8), nullable=False, server_default="debit"),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("currency", sa.String(length=3), nullable=False, server_default="INR"),
        sa.Column("amount_base_minor", sa.BigInteger(), nullable=False),
        sa.Column("fx_rate", sa.Float(), nullable=False, server_default="1.0"),
        sa.Column("raw_description", sa.Text(), nullable=False),
        sa.Column("merchant_name", sa.String(length=160), nullable=False),
        sa.Column("category", sa.String(length=48), nullable=False),
        sa.Column("category_source", sa.String(length=16), nullable=False, server_default="rule"),
        sa.Column("category_confidence", sa.Float(), nullable=False, server_default="0.9"),
        sa.Column("is_recurring", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("is_anomaly", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("anomaly_score", sa.Float(), nullable=False, server_default="0.0"),
        sa.Column("anomaly_reason", sa.String(length=255), nullable=True),
        sa.Column("external_id", sa.String(length=128), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["account_id"],
            ["bank_accounts.id"],
            name=op.f("fk_transactions_account_id_bank_accounts"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_transactions_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_transactions")),
    )
    op.create_index(op.f("ix_transactions_user_id"), "transactions", ["user_id"], unique=False)
    op.create_index(
        op.f("ix_transactions_account_id"), "transactions", ["account_id"], unique=False
    )
    op.create_index(
        op.f("ix_transactions_occurred_at"), "transactions", ["occurred_at"], unique=False
    )
    op.create_index(
        op.f("ix_transactions_merchant_name"), "transactions", ["merchant_name"], unique=False
    )
    op.create_index(op.f("ix_transactions_category"), "transactions", ["category"], unique=False)
    op.create_index(
        op.f("ix_transactions_external_id"), "transactions", ["external_id"], unique=False
    )

    op.create_table(
        "category_rules",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("merchant_pattern", sa.String(length=160), nullable=False),
        sa.Column("category", sa.String(length=48), nullable=False),
        sa.Column("hit_count", sa.Integer(), nullable=False, server_default="1"),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_category_rules_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_category_rules")),
    )
    op.create_index(op.f("ix_category_rules_user_id"), "category_rules", ["user_id"], unique=False)
    op.create_index(
        op.f("ix_category_rules_merchant_pattern"),
        "category_rules",
        ["merchant_pattern"],
        unique=False,
    )

    op.create_table(
        "budgets",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("category", sa.String(length=48), nullable=False),
        sa.Column("monthly_limit_minor", sa.BigInteger(), nullable=False),
        sa.Column("alert_threshold_pct", sa.Integer(), nullable=False, server_default="80"),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_budgets_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_budgets")),
    )
    op.create_index(op.f("ix_budgets_user_id"), "budgets", ["user_id"], unique=False)


def downgrade() -> None:
    op.drop_index(op.f("ix_budgets_user_id"), table_name="budgets")
    op.drop_table("budgets")
    op.drop_index(op.f("ix_category_rules_merchant_pattern"), table_name="category_rules")
    op.drop_index(op.f("ix_category_rules_user_id"), table_name="category_rules")
    op.drop_table("category_rules")
    op.drop_index(op.f("ix_transactions_external_id"), table_name="transactions")
    op.drop_index(op.f("ix_transactions_category"), table_name="transactions")
    op.drop_index(op.f("ix_transactions_merchant_name"), table_name="transactions")
    op.drop_index(op.f("ix_transactions_occurred_at"), table_name="transactions")
    op.drop_index(op.f("ix_transactions_account_id"), table_name="transactions")
    op.drop_index(op.f("ix_transactions_user_id"), table_name="transactions")
    op.drop_table("transactions")
    op.drop_index(op.f("ix_bank_accounts_external_id"), table_name="bank_accounts")
    op.drop_index(op.f("ix_bank_accounts_user_id"), table_name="bank_accounts")
    op.drop_table("bank_accounts")
