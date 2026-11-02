package com.pymoney.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.pymoney.R;
import com.pymoney.calc.MoneyFormat;
import com.pymoney.db.AppDb;
import com.pymoney.db.RecordDao;
import com.pymoney.db.TypeDao;
import com.pymoney.model.Entry;
import com.pymoney.model.Type;

import java.util.ArrayList;
import java.util.List;

/** 查账：按时间、类型、关键字筛记录，点进去能改能删。对应 SearchByTime / SearchByType / DelData。 */
public class SearchActivity extends Activity {

    private RangeBarView rangeBar;
    private EditText keywordInput;
    private Button typeButton;
    private Button sortButton;
    private CheckBox onlyIncome;
    private CheckBox onlyExpense;
    private ListView list;
    private HandlerThread workerThread;
    private Handler worker;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pending;
    private int generation;

    /** 打字时先等一下再查，否则每个字符都要解一遍密 */
    private static final long DEBOUNCE_MS = 250L;
    private TextView summary;
    private EntryAdapter adapter;

    private final List<Integer> selectedTypes = new ArrayList<>();
    private final List<Type> allTypes = new ArrayList<>();
    private boolean newestFirst = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        workerThread = new HandlerThread("search-query");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        adapter = new EntryAdapter(this);

        list = (ListView) findViewById(R.id.record_list);
        list.setAdapter(adapter);
        summary = (TextView) findViewById(R.id.summary);
        keywordInput = (EditText) findViewById(R.id.keyword_input);
        typeButton = (Button) findViewById(R.id.type_button);
        sortButton = (Button) findViewById(R.id.sort_button);
        onlyIncome = (CheckBox) findViewById(R.id.only_income);
        onlyExpense = (CheckBox) findViewById(R.id.only_expense);

        rangeBar = new RangeBarView(this, new String[]{"this_month", "last_month",
                "this_year", "last_year", "all"});
        ((LinearLayout) findViewById(R.id.range_bar)).addView(rangeBar,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        rangeBar.setListener(new RangeBarView.Listener() {
            @Override
            public void onRangeChanged(int start, int end) {
                refresh();
            }
        });

        TextWatcher watcher = new SimpleWatcher();
        keywordInput.addTextChangedListener(watcher);
        onlyIncome.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (checked) {
                    onlyExpense.setChecked(false);
                }
                refresh();
            }
        });
        onlyExpense.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (checked) {
                    onlyIncome.setChecked(false);
                }
                refresh();
            }
        });
        typeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTypes();
            }
        });
        sortButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newestFirst = !newestFirst;
                sortButton.setText(newestFirst ? R.string.sort_newest : R.string.sort_oldest);
                refresh();
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Intent intent = new Intent(SearchActivity.this, EditActivity.class);
                intent.putExtra(EditActivity.EXTRA_ID, adapter.getItem(position).id);
                startActivity(intent);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                confirmDelete(adapter.getItem(position));
                return true;
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (workerThread != null) {
            workerThread.quit();
            workerThread = null;
        }
        worker = null;
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rangeBar.applyPreset("this_year");
        refresh();
    }

    private void pickTypes() {
        final AppDb db = AppDb.get(this);
        allTypes.clear();
        allTypes.addAll(db.types().all());
        String[] names = new String[allTypes.size()];
        boolean[] checked = new boolean[allTypes.size()];
        for (int i = 0; i < allTypes.size(); i++) {
            names[i] = allTypes.get(i).name;
            checked[i] = selectedTypes.contains(allTypes.get(i).id);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.pick_types)
                .setMultiChoiceItems(names, checked, null)
                .setPositiveButton(R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        android.widget.ListView dialogList =
                                ((android.app.AlertDialog) dialog).getListView();
                        selectedTypes.clear();
                        for (int i = 0; i < allTypes.size(); i++) {
                            if (dialogList.isItemChecked(i)) {
                                selectedTypes.add(allTypes.get(i).id);
                            }
                        }
                        typeButton.setText(selectedTypes.isEmpty()
                                ? getString(R.string.all_types)
                                : getString(R.string.types_selected, selectedTypes.size()));
                        refresh();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDelete(final Entry entry) {
        final AppDb db = AppDb.get(this);
        Ui.confirm(this, getString(R.string.confirm_delete_title),
                String.format(getString(R.string.confirm_delete_message),
                        MoneyFormat.formatDate(entry.time), entry.name,
                        MoneyFormat.fenToYuanText(entry.amount)),
                new Runnable() {
                    @Override
                    public void run() {
                        db.records().delete(entry.id);
                        db.changed();
                        Ui.toast(SearchActivity.this, getString(R.string.deleted));
                        refresh();
                    }
                });
    }

    /**
     * 查账挪到后台线程。
     * 加密之后关键字匹配是「把区间内的记录全取出来、逐条解密再比」：
     * 一年范围上千条 ≈ 手机几十到几百毫秒，选「全部」是一万两千条；
     * 而输入框每个字符都会触发一次，留在主线程就是 ANR。
     */
    private void refresh() {
        final RecordDao.Filter filter = new RecordDao.Filter();
        filter.start = rangeBar.start();
        filter.end = rangeBar.end();
        filter.newestFirst = newestFirst;
        filter.keyword = keywordInput.getText().toString();
        filter.incomeOnly = onlyIncome.isChecked();
        filter.expenseOnly = onlyExpense.isChecked();
        for (Integer typeId : selectedTypes) {
            filter.addType(typeId);
        }
        if (worker == null) {
            render(searchNow(filter));
            return;
        }
        final boolean typing = filter.keyword != null && !filter.keyword.trim().isEmpty();
        final int mine = ++generation;
        if (pending != null) {
            worker.removeCallbacks(pending);
        }
        pending = new Runnable() {
            @Override
            public void run() {
                final List<Entry> entries = searchNow(filter);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine == generation) {
                            render(entries);     // 只认最后一次点下去的那次，早先的结果丢掉
                        }
                    }
                });
            }
        };
        worker.postDelayed(pending, typing ? DEBOUNCE_MS : 0);
    }

    private List<Entry> searchNow(RecordDao.Filter filter) {
        return AppDb.get(getApplicationContext()).records().search(filter);
    }

    private void render(List<Entry> entries) {
        adapter.setItems(entries);

        long income = 0L;
        long expense = 0L;
        for (Entry entry : entries) {
            if (entry.amount >= 0) {
                income += entry.amount;
            } else {
                expense += entry.amount;
            }
        }
        if (entries.isEmpty()) {
            summary.setText(R.string.search_empty);
        } else {
            summary.setText(String.format(getString(R.string.search_summary), entries.size(),
                    MoneyFormat.fenToYuanText(income + expense),
                    MoneyFormat.fenToYuanText(income),
                    MoneyFormat.fenToYuanText(Math.abs(expense))));
        }
    }

    private class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            refresh();
        }
    }
}
