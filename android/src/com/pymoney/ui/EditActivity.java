package com.pymoney.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import com.pymoney.R;
import com.pymoney.calc.MoneyFormat;
import com.pymoney.core.Codec;
import com.pymoney.db.AppDb;
import com.pymoney.db.TypeDao;
import com.pymoney.model.Entry;
import com.pymoney.model.Type;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 记一笔，同时也是编辑已有记录。对应原程序的 InsertData()。 */
public class EditActivity extends Activity {

    public static final String EXTRA_ID = "id";

    private Button dateButton;
    private EditText nameInput;
    private EditText amountInput;
    private RadioGroup direction;
    private Spinner typeSpinner;
    private TextView typeHint;
    private Button deleteButton;

    private final List<Integer> typeIds = new ArrayList<>();
    private int date;
    private long editingId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);
        setTitle(R.string.title_edit);

        dateButton = (Button) findViewById(R.id.date_button);
        nameInput = (EditText) findViewById(R.id.name_input);
        amountInput = (EditText) findViewById(R.id.amount_input);
        direction = (RadioGroup) findViewById(R.id.direction);
        typeSpinner = (Spinner) findViewById(R.id.type_spinner);
        typeHint = (TextView) findViewById(R.id.type_hint);
        deleteButton = findViewById(R.id.delete_button);

        editingId = getIntent().getLongExtra(EXTRA_ID, 0L);
        date = MoneyFormat.today();

        final AppDb db = AppDb.get(this);
        if (editingId > 0) {
            Entry existing = db.records().findById(editingId);
            if (existing != null) {
                date = existing.time;
                nameInput.setText(existing.name);
                amountInput.setText(MoneyFormat.fenToYuan(Math.abs(existing.amount)).toPlainString());
                direction.check(existing.amount > 0
                        ? R.id.direction_income : R.id.direction_expense);
                setTitle(R.string.title_edit_existing);
                deleteButton.setVisibility(View.VISIBLE);
                pendingTypeId = existing.typeId;
            }
        }

        loadTypes(db.types());
        if (pendingTypeId <= 0) {
            pendingTypeId = db.records().lastUsedTypeId();
        }

        dateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.pickDate(EditActivity.this, date, new Ui.DateCallback() {
                    @Override
                    public void onDate(int year, int month, int day) {
                        date = MoneyFormat.toDateInt(year, month, day);
                        dateButton.setText(MoneyFormat.formatDate(date));
                    }
                });
            }
        });
        findViewById(R.id.today_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                date = MoneyFormat.today();
                dateButton.setText(MoneyFormat.formatDate(date));
            }
        });
        findViewById(R.id.new_type_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.input(EditActivity.this, getString(R.string.dlg_new_type),
                        getString(R.string.dlg_new_type_hint), null, false,
                        new Ui.TextCallback() {
                            @Override
                            public void onText(String text) {
                                if (text.isEmpty()) {
                                    return;
                                }
                                long id = AppDb.get(EditActivity.this).types().ensureType(text);
                                pendingTypeId = (int) id;
                                loadTypes(AppDb.get(EditActivity.this).types());
                            }
                        });
            }
        });
        findViewById(R.id.save_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });
        dateButton.setText(MoneyFormat.formatDate(date));

        // 从桌面小组件直接进来时可能还没解锁：上面填的是掩码，口令输对再回填一次。
        // 这一页是小组件拉起来的临时页，不解锁就没得可填，所以取消时关掉它回桌面。
        if (!db.crypto().unlocked()) {
            LockGate.ensureOrFinish(this, new LockGate.Ready() {
                @Override
                public void onReady() {
                    refillPlain();
                }
            });
        }
    }

    private void refillPlain() {
        AppDb db = AppDb.get(this);
        if (editingId > 0) {
            Entry existing = db.records().findById(editingId);
            if (existing != null && existing.name != null && !Codec.LOCKED_MASK.equals(existing.name)) {
                nameInput.setText(existing.name);
            }
        }
        loadTypes(db.types());
    }

    private int pendingTypeId;

    private void loadTypes(TypeDao types) {
        List<Type> all = types.all();
        List<String> names = new ArrayList<>();
        typeIds.clear();
        int selected = 0;
        for (int i = 0; i < all.size(); i++) {
            names.add(all.get(i).name);
            typeIds.add(all.get(i).id);
            if (all.get(i).id == pendingTypeId) {
                selected = i;
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(adapter);
        typeSpinner.setSelection(selected);
        typeHint.setText(typeIds.isEmpty() ? "还没有类型，点右边的「新建」加一个" : "");
    }

    private void save() {
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            Ui.toast(this, getString(R.string.err_empty_name));
            return;
        }
        if (!MoneyFormat.isValidDate(date)) {
            Ui.toast(this, getString(R.string.err_invalid_date));
            return;
        }
        long fen;
        try {
            fen = new BigDecimal(amountInput.getText().toString().trim()).movePointRight(2)
                    .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (RuntimeException e) {
            Ui.toast(this, getString(R.string.err_bad_amount));
            return;
        }
        if (fen == 0) {
            Ui.toast(this, getString(R.string.err_bad_amount));
            return;
        }
        if (direction.getCheckedRadioButtonId() == R.id.direction_expense) {
            fen = -Math.abs(fen);
        } else {
            fen = Math.abs(fen);
        }
        int position = typeSpinner.getSelectedItemPosition();
        if (position < 0 || position >= typeIds.size()) {
            Ui.toast(this, getString(R.string.err_no_type));
            return;
        }
        int typeId = typeIds.get(position);

        AppDb db = AppDb.get(this);
        if (!db.crypto().unlocked()) {
            // 锁着的时候写进去的是明文，LockedCodec 会直接抛；在这儿拦住，别弹堆栈
            Ui.toast(this, getString(R.string.err_locked_write));
            return;
        }
        if (editingId > 0) {
            Entry entry = new Entry(editingId, date, name, fen, typeId, null);
            db.records().update(entry);
            db.changed();
            Ui.toast(this, getString(R.string.saved_updated));
            finish();
            return;
        }
        db.records().insert(new Entry(0, date, name, fen, typeId, null));
        db.changed();
        pendingTypeId = typeId;
        Ui.toast(this, getString(R.string.saved_inserted) + " "
                + MoneyFormat.fenToYuanText(fen) + " 元");
        // 留在页面上方便连续记，只清掉名称和金额
        nameInput.setText("");
        amountInput.setText("");
        nameInput.requestFocus();
    }

    private void confirmDelete() {
        if (editingId <= 0) {
            return;
        }
        final AppDb db = AppDb.get(this);
        final Entry entry = db.records().findById(editingId);
        if (entry == null) {
            Ui.toast(this, "这条记录已经不在了");
            finish();
            return;
        }
        Ui.confirm(this, getString(R.string.confirm_delete_title),
                String.format(getString(R.string.confirm_delete_message),
                        MoneyFormat.formatDate(entry.time), entry.name,
                        MoneyFormat.fenToYuanText(entry.amount)),
                new Runnable() {
                    @Override
                    public void run() {
                        db.records().delete(entry.id);
                        db.changed();
                        Ui.toast(EditActivity.this, getString(R.string.deleted));
                        finish();
                    }
                });
    }
}
