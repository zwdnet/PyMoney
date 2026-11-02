package com.pymoney.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.pymoney.R;
import com.pymoney.calc.MoneyFormat;
import com.pymoney.db.AppDb;
import com.pymoney.model.Type;

import java.util.ArrayList;
import java.util.List;

/** 类型管理：新增、改名、删除；删掉还有记录的类时，可以先把记录并入别的类型。 */
public class TypeManageActivity extends Activity {

    private AppDb db;
    private final List<Type> items = new ArrayList<>();
    private TypeAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_types);
        db = AppDb.get(this);

        ListView list = (ListView) findViewById(R.id.type_list);
        adapter = new TypeAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.add_type).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.input(TypeManageActivity.this, getString(R.string.type_add),
                        getString(R.string.dlg_new_type_hint), "", false,
                        new Ui.TextCallback() {
                            @Override
                            public void onText(String text) {
                                if (text.isEmpty()) {
                                    return;
                                }
                                if (db.types().idByName(text) != null) {
                                    Ui.toast(TypeManageActivity.this,
                                            getString(R.string.type_exists));
                                    return;
                                }
                                db.types().ensureType(text);
                                db.changed();
                                refresh();
                            }
                        });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        items.clear();
        items.addAll(db.types().all());
        adapter.notifyDataSetChanged();
    }

    private void rename(final Type type) {
        Ui.input(this, getString(R.string.dlg_rename), getString(R.string.dlg_new_type_hint),
                type.name, false, new Ui.TextCallback() {
                    @Override
                    public void onText(String text) {
                        if (text.isEmpty() || text.equals(type.name)) {
                            return;
                        }
                        if (!db.types().rename(type.id, text)) {
                            Ui.toast(TypeManageActivity.this, getString(R.string.type_exists));
                            return;
                        }
                        db.changed();
                        Ui.toast(TypeManageActivity.this, getString(R.string.type_renamed));
                        refresh();
                    }
                });
    }

    private void delete(final Type type) {
        if (type.count == 0) {
            Ui.confirm(this, getString(R.string.type_delete),
                    getString(R.string.type_delete_last, String.valueOf(type.count),
                            MoneyFormat.fenToYuanText(type.amount)),
                    new Runnable() {
                        @Override
                        public void run() {
                            db.types().delete(type.id);
                            db.changed();
                            Ui.toast(TypeManageActivity.this, getString(R.string.type_deleted));
                            refresh();
                        }
                    });
            return;
        }
        final List<Type> targets = new ArrayList<>();
        for (Type other : items) {
            if (other.id != type.id) {
                targets.add(other);
            }
        }
        if (targets.isEmpty()) {
            Ui.toast(this, getString(R.string.type_summary, type.count,
                    MoneyFormat.fenToYuanText(type.amount)));
            return;
        }
        String[] labels = new String[targets.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = targets.get(i).name;
        }
        Ui.choose(this, getString(R.string.type_move_records), labels, new Ui.TextCallback() {
            @Override
            public void onText(String text) {
                Type target = targets.get(Integer.parseInt(text));
                db.types().deleteAndMove(type.id, target.id);
                db.changed();
                Ui.toast(TypeManageActivity.this,
                        getString(R.string.type_moved, target.name));
                refresh();
            }
        });
    }

    private class TypeAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Type getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).id;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.item_type, parent, false);
            }
            final Type type = getItem(position);
            ((TextView) view.findViewById(R.id.type_id)).setText(String.valueOf(type.id));
            ((TextView) view.findViewById(R.id.type_name)).setText(type.name);
            ((TextView) view.findViewById(R.id.type_stat)).setText(
                    getString(R.string.type_summary, (int) type.count,
                            MoneyFormat.fenToYuanText(type.amount)));
            Button rename = (Button) view.findViewById(R.id.type_rename);
            Button delete = (Button) view.findViewById(R.id.type_delete);
            delete.setText(type.count == 0 ? getString(R.string.type_delete)
                    : getString(R.string.type_move_records));
            rename.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    TypeManageActivity.this.rename(type);
                }
            });
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    TypeManageActivity.this.delete(type);
                }
            });
            return view;
        }
    }
}
