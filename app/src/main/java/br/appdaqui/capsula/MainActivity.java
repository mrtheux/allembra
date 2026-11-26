package br.appdaqui.capsula;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    static final int BG = 0xFFF9F6ED, INK = 0xFF27232B, CARD = 0xFFEFE7F9;
    static final int GOLD = 0xFFF0D788, MUTED = 0xFF5E5266;
    static final int PHOTO = 20, VIDEO = 21, UNLOCK = 22;
    static final int VIDEO_SECONDS = 30, AUDIO_SECONDS = 60;
    static final String[] TYPES = {"Texto","Pensamento","Foto","Vídeo","Áudio"};
    static final String[] TERMS = {"1 mês","3 meses","6 meses","1 ano","1 ano e 6 meses","3 anos","Surpresa (3 meses a 3 anos)"};
    static final int[] MONTHS = {1,3,6,12,18,36};
    static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final Handler handler = new Handler(Looper.getMainLooper());
    final List<String> previewFiles = new ArrayList<>();
    Vault vault;
    Appwrite cloud;
    LinearLayout body;
    String type = "Texto", capture;
    byte[] draftMedia;
    String draftText = "";
    EditText editor;
    MediaRecorder recorder;
    String audioFile;
    boolean unlocked, wantRecord, busy;
    long backgroundAt;
    Runnable stopAudio = this::finishAudio;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        vault = new Vault(this);
        cloud = new Appwrite(this);
        capture = state == null ? null : state.getString("capture");
        if (state != null) type = state.getString("type", "Texto");
        wantRecord = getIntent().getBooleanExtra("record", false) || capture != null;
        getIntent().removeExtra("record");
        File[] cache = getCacheDir().listFiles((dir, name) -> name.startsWith("daqui-") && !name.equals("daqui-" + capture));
        if (cache != null) for (File f : cache) f.delete();
        gate();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("capture",capture);
        state.putString("type",type);
        super.onSaveInstanceState(state);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("record",false)) {
            wantRecord = true;
            if (unlocked && !busy) { wantRecord = false; resetDraft(); compose(); }
        }
    }

    @Override protected void onStop() {
        if (recorder != null) finishAudio();
        backgroundAt = SystemClock.elapsedRealtime();
        super.onStop();
    }

    @Override protected void onRestart() {
        super.onRestart();
        if (unlocked && capture == null && SystemClock.elapsedRealtime() - backgroundAt >= 120_000) {
            unlocked = false;
            clearPreviews();
            gate();
        }
    }

    @Override protected void onDestroy() {
        if (recorder != null) discardRecording();
        clearPreviews();
        worker.shutdown();
        super.onDestroy();
    }

    void gate() {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (!keyguard.isDeviceSecure()) { enter(); return; }
        page("Allembra", "Abra seu arquivo pessoal");
        button("Desbloquear com o Android", true, () -> startActivityForResult(
            keyguard.createConfirmDeviceCredentialIntent("Allembra", "Confirme o bloqueio do aparelho"), UNLOCK));
    }

    void enter() {
        unlocked = true;
        if (wantRecord || draftMedia != null) { wantRecord = false; compose(); }
        else home();
    }

    int dp(int px) { return Math.round(px * getResources().getDisplayMetrics().density); }

    GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    void page(String title, String subtitle) {
        editor = null;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24),dp(14),dp(24),dp(24));
        scroll.addView(body);
        setContentView(scroll);
        View root = getWindow().getDecorView();
        root.setOnApplyWindowInsetsListener((v, i) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets system = i.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = i.getInsets(WindowInsets.Type.ime());
                scroll.setPadding(system.left, system.top, system.right, Math.max(system.bottom, ime.bottom));
            }
            return i;
        });
        if (Build.VERSION.SDK_INT >= 35) root.requestApplyInsets();
        else root.setOnApplyWindowInsetsListener(null);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(br.appdaqui.capsula.R.drawable.ic_daqui);
        heading.addView(icon,new LinearLayout.LayoutParams(dp(40),dp(40)));
        TextView titleView = label(title,36,INK);
        titleView.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-2,-2);
        placement.leftMargin = dp(10);
        heading.addView(titleView, placement);
        body.addView(heading);
        if (!subtitle.isEmpty()) note(subtitle);
        space(12);
    }

    void note(String text) { body.addView(label(text,16,MUTED)); space(8); }

    void space(int height) { body.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height))); }

    void heading(String text) {
        TextView view = label(text, 22, INK);
        view.setTypeface(Typeface.create("serif",Typeface.BOLD));
        body.addView(view);
        space(4);
    }

    void button(String text, boolean primary, Runnable action) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(text);
        view.setTextSize(16);
        view.setTextColor(INK);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setPadding(dp(17),dp(10),dp(17),dp(10));
        view.setMinHeight(dp(52));
        view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000),shape(primary ? GOLD : CARD,16),null));
        view.setOnClickListener(v -> { if (!busy) action.run(); });
        body.addView(view,new LinearLayout.LayoutParams(-1,-2));
        space(8);
    }

    EditText field(String hint, int inputType, int length) {
        EditText field = new EditText(this);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setTextSize(17);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(length)});
        field.setBackground(shape(Color.WHITE,12));
        field.setPadding(dp(14),dp(12),dp(14),dp(12));
        return field;
    }

    void home() {
        clearPreviews();
        page("Allembra", "De mim, para mim, daqui a um tempo.");
        button("Registrar um momento", true, () -> { resetDraft(); compose(); });
        button("Minhas cápsulas",false,this::archive);
        button("Lembretes",false,this::reminders);
        button("Conta e backup",false,this::account);
    }

    void reminders() {
        page("Lembretes","Escolha a frequência de registro.");
        android.content.SharedPreferences prefs = getSharedPreferences("settings",0);
        int current = prefs.getInt("hours",0);
        note(current == 0 ? "Lembretes desligados" : "A cada " + current + " h");
        EditText hours = field("Intervalo em horas (1 a 8.760)", InputType.TYPE_CLASS_NUMBER,4);
        if (current != 0) hours.setText(String.valueOf(current));
        body.addView(hours);
        space(8);
        button("Ativar lembretes",true,() -> {
            int value;
            try { value = Integer.parseInt(hours.getText().toString()); }
            catch (NumberFormatException error) { value = 0; }
            if (value < 1 || value > 8760) { hours.setError("Informe de 1 a 8.760 horas."); return; }
            prefs.edit().putInt("hours",value).apply();
            Reminder.schedule(this);
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},30);
            reminders();
        });
        button("Desligar",false,() -> { prefs.edit().remove("hours").apply(); Reminder.schedule(this); reminders(); });
        if (current != 0 && !getSystemService(NotificationManager.class).areNotificationsEnabled())
            button("Abrir permissões de notificação",false,() -> startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())));
        button("Voltar",false,this::home);
        note("O Android pode adiar o lembrete para economizar bateria.");
    }

    void resetDraft() { discardRecording(); type = "Texto"; draftText = ""; draftMedia = null; capture = null; }

    void compose() {
        page("Registrar", "O que você quer guardar?");
        Spinner picker = new Spinner(this);
        picker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,TYPES));
        picker.setSelection(Arrays.asList(TYPES).indexOf(type));
        picker.setContentDescription("Tipo de registro");
        body.addView(picker,new LinearLayout.LayoutParams(-1,dp(52)));
        picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (!type.equals(TYPES[position])) {
                    discardRecording();
                    type = TYPES[position];
                    draftText = "";
                    draftMedia = null;
                    compose();
                }
            }
        });
        space(8);
        if (type.equals("Texto") || type.equals("Pensamento")) {
            editor = field(type.equals("Texto") ? "Escreva sobre este momento" : "O que está passando pela sua cabeça?",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,4000);
            editor.setGravity(Gravity.TOP);
            editor.setMinLines(6);
            editor.setText(draftText);
            body.addView(editor,new LinearLayout.LayoutParams(-1,-2));
            space(8);
        } else if (type.equals("Áudio")) {
            if (recorder != null) button("Parar gravação",true,this::finishAudio);
            else button(draftMedia == null ? "Gravar áudio (até 60 s)" : "Gravar outro áudio",false,this::startAudio);
        } else {
            button(type.equals("Foto") ? "Abrir câmera para foto" : "Gravar vídeo (até 30 s)",false,this::startCamera);
        }
        if (draftMedia != null) note("Mídia pronta para guardar");
        if (recorder == null) button("Escolher data de abertura",true,() -> {
            if (editor != null) draftText = editor.getText().toString().trim();
            if (draftText.isEmpty() && draftMedia == null) { message("Adicione o conteúdo do registro."); return; }
            opening();
        });
        button("Cancelar",false,() -> new AlertDialog.Builder(this).setMessage("Descartar este registro?")
            .setNegativeButton("Continuar editando",null).setPositiveButton("Descartar",(d,w) -> { resetDraft(); home(); }).show());
    }

    void startCamera() {
        boolean photo = type.equals("Foto");
        capture = "captura-" + UUID.randomUUID() + (photo ? ".jpg" : ".mp4");
        Uri uri = MediaBridge.uri(capture);
        Intent intent = new Intent(photo ? MediaStore.ACTION_IMAGE_CAPTURE : MediaStore.ACTION_VIDEO_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT,uri);
        intent.setClipData(ClipData.newRawUri("captura",uri));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!photo) intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT,VIDEO_SECONDS)
            .putExtra(MediaStore.EXTRA_SIZE_LIMIT,(long)Vault.MAX_MEDIA).putExtra(MediaStore.EXTRA_VIDEO_QUALITY,0);
        try { startActivityForResult(intent,photo ? PHOTO : VIDEO); }
        catch (ActivityNotFoundException e) { capture = null; message("O aparelho não tem um app de câmera disponível."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (request == UNLOCK) { if (result == RESULT_OK) enter(); return; }
        if (request != PHOTO && request != VIDEO || capture == null) return;
        Uri output = MediaBridge.uri(capture);
        File file = MediaBridge.file(this,capture);
        revokeUriPermission(output,Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        capture = null;
        if (result == RESULT_OK) {
            try {
                Uri source = file.length() > 0 ? output : data == null ? null : data.getData();
                if (source == null) throw new IOException();
                byte[] bytes = Vault.read(getContentResolver().openInputStream(source),Vault.MAX_MEDIA);
                if (bytes.length == 0) throw new IOException();
                if (request == VIDEO) {
                    MediaMetadataRetriever metadata = new MediaMetadataRetriever();
                    try {
                        metadata.setDataSource(this,source);
                        long duration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                        if (duration <= 0 || duration > VIDEO_SECONDS * 1000L) throw new IOException();
                    } finally { metadata.release(); }
                }
                draftMedia = bytes;
            } catch (Exception e) { message(request == VIDEO ? "Use um vídeo de até 30 s e 12 MB." : "Não foi possível guardar a foto. O limite é 12 MB."); }
        }
        file.delete();
        if (unlocked) compose();
    }

    void startAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},31);
            return;
        }
        audioFile = "captura-" + UUID.randomUUID() + ".m4a";
        try {
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setOutputFile(MediaBridge.file(this,audioFile).getAbsolutePath());
            recorder.prepare();
            recorder.start();
            handler.postDelayed(stopAudio, AUDIO_SECONDS * 1000L);
            compose();
        } catch (Exception error) { discardRecording(); message("Não foi possível iniciar a gravação."); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if (code == 31 && unlocked && type.equals("Áudio")) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startAudio();
            else message("Autorize o microfone nas configurações do Android para gravar áudio.");
        }
        if (code == 30 && unlocked) reminders();
    }

    void finishAudio() {
        if (recorder == null) return;
        try {
            recorder.stop();
            byte[] bytes = Vault.read(new FileInputStream(MediaBridge.file(this,audioFile)),Vault.MAX_MEDIA);
            if (bytes.length == 0) throw new IOException();
            draftMedia = bytes;
        } catch (Exception error) { message("Não foi possível guardar a gravação."); }
        discardRecording();
        if (unlocked) compose();
    }

    void discardRecording() {
        handler.removeCallbacks(stopAudio);
        if (recorder != null) { recorder.release(); recorder = null; }
        if (audioFile != null) { MediaBridge.file(this,audioFile).delete(); audioFile = null; }
    }

    static String date(long stamp) {
        return DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' uuuu",new Locale("pt","BR"))
            .format(Instant.ofEpochMilli(stamp).atZone(BRASILIA));
    }

    static String openingDate(long stamp) {
        return DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' uuuu 'às' HH:mm '· Brasília'", new Locale("pt","BR"))
            .format(Instant.ofEpochMilli(stamp).atZone(BRASILIA));
    }

    static long deadline(ZonedDateTime now, int term) {
        now = now.withZoneSameInstant(BRASILIA);
        if (term < MONTHS.length) return now.plusMonths(MONTHS[term]).toInstant().toEpochMilli();
        ZonedDateTime earliest = now.plusMonths(3), latest = now.plusYears(3);
        int span = (int)ChronoUnit.DAYS.between(earliest.toLocalDate(),latest.toLocalDate());
        return earliest.plusDays(Vault.RANDOM.nextInt(span + 1)).toInstant().toEpochMilli();
    }

    void opening() {
        page("Selar registro","A abertura segue o horário de Brasília.");
        button("Abrir este registro sozinho",true,() -> chooseTerm(""));
        button("Criar um compilado",false,() -> {
            EditText name = field("Nome do compilado",InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,60);
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Novo compilado").setView(name)
                .setNegativeButton("Cancelar",null).setPositiveButton("Escolher prazo",null).create();
            dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String title = name.getText().toString().trim();
                if (title.isEmpty()) { name.setError("Digite um nome."); return; }
                dialog.dismiss(); chooseTerm(title);
            }));
            dialog.show();
        });
        button("Adicionar a um compilado existente",false,() -> run("Carregando compilados…",this::readSummaries, items -> {
            Map<String,JSONObject> groups = new LinkedHashMap<>();
            for (JSONObject meta : items) if (!meta.optString("groupId").isEmpty() && meta.optLong("opens") > System.currentTimeMillis())
                groups.putIfAbsent(meta.optString("groupId"),meta);
            List<JSONObject> choices = new ArrayList<>(groups.values());
            if (choices.isEmpty()) { message("Nenhum compilado fechado. Crie um para começar."); return; }
            String[] labels = choices.stream().map(x -> x.optString("group") + " · " + (x.optBoolean("random") ? "Data surpresa" : openingDate(x.optLong("opens")))).toArray(String[]::new);
            new AlertDialog.Builder(this).setTitle("Em qual compilado?").setItems(labels,(d,w) -> {
                JSONObject meta = choices.get(w);
                save(meta.optString("group"),meta.optString("groupId"),meta.optLong("opens"),meta.optBoolean("random"));
            }).show();
        }));
        button("Voltar ao registro",false,this::compose);
    }

    void chooseTerm(String group) {
        new AlertDialog.Builder(this).setTitle("Quando abrir?").setItems(TERMS,(dialog, term) ->
            save(group,group.isEmpty() ? "" : UUID.randomUUID().toString(),deadline(ZonedDateTime.now(BRASILIA),term),term == MONTHS.length)).show();
    }

    void save(String group, String groupId, long opens, boolean random) {
        if (opens <= System.currentTimeMillis()) { message("Esse compilado já abriu."); return; }
        final String storedType = type, storedText = draftText;
        final byte[] storedMedia = draftMedia == null ? new byte[0] : draftMedia;
        run("Selando seu registro…",() -> {
            JSONObject meta = new JSONObject().put("id",UUID.randomUUID().toString()).put("type",storedType)
                .put("created",System.currentTimeMillis()).put("opens",opens).put("random",random)
                .put("group",group).put("groupId",groupId).put("text",storedText);
            if (meta.getLong("created") >= opens) throw new IOException("Esse compilado já abriu.");
            vault.save(new Vault.Entry(meta,storedMedia));
            return true;
        }, saved -> { resetDraft(); home(); message(random ? "Selado. A data é uma surpresa." : "Selado até " + openingDate(opens) + "."); });
    }

    List<JSONObject> readSummaries() throws Exception {
        List<JSONObject> result = new ArrayList<>();
        for (String id : vault.ids()) result.add(vault.load(id).meta);
        result.sort(Comparator.comparingLong(meta -> meta.optLong("opens")));
        return result;
    }

    void archive() {
        clearPreviews();
        page("Minhas cápsulas","Registros guardados e lembranças disponíveis.");
        button("Voltar",false,this::home);
        run("Carregando cápsulas…",this::readSummaries,items -> {
            if (items.isEmpty()) { note("Seu primeiro registro vai aparecer aqui."); return; }
            Map<String,List<JSONObject>> groups = new LinkedHashMap<>();
            for (JSONObject meta : items) {
                String group = meta.optString("groupId");
                groups.computeIfAbsent(group.isEmpty() ? meta.optString("id") : group, key -> new ArrayList<>()).add(meta);
            }
            long now = System.currentTimeMillis();
            for (boolean available : new boolean[]{true,false}) {
                heading(available ? "Disponíveis" : "Seladas");
                int count = 0;
                for (List<JSONObject> group : groups.values()) {
                    JSONObject first = group.get(0);
                    if ((first.optLong("opens") <= now) != available) continue;
                    count++;
                    String title = first.optString("group");
                    if (title.isEmpty()) title = first.optString("type") + " de " + date(first.optLong("created"));
                    else title += " · " + group.size() + (group.size() == 1 ? " registro" : " registros");
                    String detail = !available && first.optBoolean("random") ? "Data surpresa" : openingDate(first.optLong("opens"));
                    if (available) button(title + "\n" + detail,false,() -> showGroup(group));
                    else {
                        TextView sealed = label(title + "\n" + detail,16,INK);
                        sealed.setBackground(shape(CARD,14));
                        sealed.setPadding(dp(16),dp(12),dp(16),dp(12));
                        body.addView(sealed);
                        space(8);
                    }
                }
                if (count == 0) note(available ? "Nenhuma cápsula abriu ainda." : "Nenhuma cápsula aguardando abertura.");
            }
        });
    }

    void showGroup(List<JSONObject> items) {
        String title = items.get(0).optString("group");
        page(title.isEmpty() ? "Lembrança" : title, "Abertura em " + openingDate(items.get(0).optLong("opens")));
        button("Voltar",false,this::archive);
        items.sort(Comparator.comparingLong(x -> x.optLong("created")));
        for (JSONObject item : items) button(item.optString("type") + " · " + date(item.optLong("created")),false,() -> openEntry(item.optString("id"),items));
    }

    void openEntry(String id, List<JSONObject> group) {
        clearPreviews();
        run("Abrindo sua lembrança…",() -> {
            Vault.Entry entry = vault.load(id);
            if (entry.opens() > System.currentTimeMillis()) throw new IOException("O registro está selado.");
            return entry;
        },entry -> {
            String type = entry.meta.optString("type");
            page(type,date(entry.meta.optLong("created")));
            button("Voltar",false,() -> { clearPreviews(); showGroup(group); });
            if (entry.media.length == 0) { body.addView(label(entry.meta.optString("text"),19,INK)); return; }
            if (type.equals("Foto")) {
                ImageView image = new ImageView(this);
                BitmapFactory.Options size = new BitmapFactory.Options();
                size.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(entry.media,0,entry.media.length,size);
                int sample = 1;
                while (Math.max(size.outWidth,size.outHeight) / sample > 2048) sample *= 2;
                size.inJustDecodeBounds = false;
                size.inSampleSize = sample;
                image.setImageBitmap(BitmapFactory.decodeByteArray(entry.media,0,entry.media.length,size));
                image.setContentDescription("Foto guardada");
                image.setAdjustViewBounds(true);
                body.addView(image,new LinearLayout.LayoutParams(-1,-2));
            } else {
                try {
                    boolean video = type.equals("Vídeo");
                    String name = "aberto-" + entry.id() + (video ? ".mp4" : ".m4a");
                    previewFiles.add(name);
                    File file = MediaBridge.file(this,name);
                    try (FileOutputStream output = new FileOutputStream(file)) { output.write(entry.media); }
                    VideoView player = new VideoView(this);
                    MediaController controls = new MediaController(this);
                    controls.setAnchorView(player);
                    player.setMediaController(controls);
                    player.setVideoPath(file.getAbsolutePath());
                    body.addView(player,new LinearLayout.LayoutParams(-1,dp(video ? 225 : 70)));
                    button("Reproduzir",true,() -> { player.start(); controls.show(); });
                } catch (IOException e) { clearPreviews(); message("Não foi possível reproduzir esta mídia."); }
            }
        });
    }

    void clearPreviews() {
        for (String file : previewFiles) MediaBridge.file(this,file).delete();
        previewFiles.clear();
    }

    void account() {
        page("Conta e backup","Cópia criptografada e restauração.");
        if (!cloud.configured()) {
            note("A nuvem ainda precisa ser configurada. Seus registros ficam neste aparelho.");
        } else if (cloud.userId == null) {
            button("Entrar",true,() -> authForm("login","Entrar"));
            button("Criar conta",false,() -> authForm("signup","Criar conta"));
            if (Appwrite.validLink(cloud.recoveryUrl)) button("Redefinir senha",false,() -> authForm("recover","Redefinir senha"));
        } else {
            note(cloud.email);
            if (!cloud.verified) {
                note("Confirme seu e-mail para usar o backup.");
                if (Appwrite.validLink(cloud.verificationUrl)) button("Enviar e-mail de confirmação",true,() ->
                    run("Enviando…",() -> { cloud.sendVerification(); return true; }, sent -> message("Confira sua caixa de e-mail.")));
                button("Já confirmei o e-mail",false,() ->
                    run("Consultando a conta…",() -> { cloud.refreshAccount(); return cloud.verified; }, checked -> account()));
            } else {
                button("Gerar código de recuperação",false,this::newCode);
                button("Salvar cópia na nuvem",true,() -> backupForm(true));
                button("Restaurar registros da nuvem",false,() -> backupForm(false));
            }
            button("Sair da conta",false,() ->
                run("Encerrando sessão…",() -> {
                    try { cloud.logout(); return true; } catch (Exception e) { return false; }
                }, signedOut -> { account(); if (!signedOut) message("A sessão local foi encerrada. O servidor não confirmou o encerramento."); }));
        }
        button("Voltar",false,this::home);
    }

    void authForm(String operation,String title) {
        page(title,"Use seu e-mail para acessar o backup.");
        EditText email = field("E-mail",InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,254);
        body.addView(email);
        space(8);
        EditText password = field("Senha",InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,128);
        boolean recovery = operation.equals("recover");
        boolean signup = operation.equals("signup");
        if (!recovery) { body.addView(password); space(8); }
        if (signup) note("Use pelo menos 12 caracteres.");
        button(title,true,() -> {
            String address = email.getText().toString().trim();
            String secret = password.getText().toString();
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) { email.setError("Confira o e-mail."); return; }
            if (signup && secret.length() < 12) { password.setError("A senha precisa ter pelo menos 12 caracteres."); return; }
            if (!signup && !recovery && secret.isEmpty()) { password.setError("Digite a senha."); return; }
            run("Enviando…",() -> {
                if (recovery) cloud.recover(address);
                else if (signup) cloud.createAccount(address,secret);
                else cloud.login(address,secret);
                return true;
            }, success -> {
                password.setText("");
                account();
                if (recovery) message("Se o endereço estiver cadastrado, você receberá um e-mail.");
                if (signup) message("Conta criada. Entre para confirmar seu e-mail.");
            });
        });
        button("Voltar",false,this::account);
    }

    void newCode() {
        page("Código de recuperação","Guarde o código em um gerenciador de senhas. Você precisará dele para salvar e restaurar cópias.");
        EditText code = field("Código",InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,43);
        code.setText(Appwrite.generateCode());
        code.setKeyListener(null);
        code.setTextIsSelectable(true);
        body.addView(code);
        space(12);
        note("Se já fez um backup, continue usando o código original. Redefinir a senha não recupera o código.");
        button("Voltar",false,this::account);
    }

    void backupForm(boolean upload) {
        page(upload ? "Salvar cópia" : "Restaurar","Informe o código de recuperação da conta.");
        EditText code = field("Código de recuperação",InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,43);
        body.addView(code);
        space(8);
        button(upload ? "Enviar registros" : "Restaurar registros",true,() -> {
            String secret = code.getText().toString().trim();
            if (!secret.matches("[A-Za-z0-9_-]{43}")) { code.setError("Confira o código de 43 caracteres."); return; }
            android.content.SharedPreferences prefs = getSharedPreferences("settings",0);
            String scope = cloud.scope();
            String linked = prefs.getString("backupOwner","");
            if (!linked.isEmpty() && !linked.equals(scope)) {
                message("O arquivo local está vinculado a outra conta de backup."); return;
            }
            run(upload ? "Enviando cópia…" : "Restaurando cópia…",() -> {
                javax.crypto.SecretKey key = Appwrite.recoveryKey(secret);
                cloud.verify(key,upload);
                if (!prefs.edit().putString("backupOwner",scope).commit()) throw new IOException();
                return cloud.transfer(vault,key,upload);
            },count -> {
                code.setText("");
                account();
                message(count + (upload ? " registro(s) enviado(s)." : " registro(s) restaurado(s)."));
            });
        });
        button("Voltar",false,this::account);
        note("Os registros existentes são preservados. O backup é manual.");
    }

    interface Job<T> { T work() throws Exception; }
    void message(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }

    <T> void run(String prompt, Job<T> job, java.util.function.Consumer<T> done) {
        if (busy) return;
        busy = true;
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage(prompt);
        progress.setCancelable(false);
        progress.show();
        worker.execute(() -> {
            try {
                T value = job.work();
                runOnUiThread(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    progress.dismiss();
                    if (unlocked) done.accept(value);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    progress.dismiss();
                    if (!unlocked) return;
                    if (failure instanceof Appwrite.HttpError) {
                        int status = ((Appwrite.HttpError)failure).status;
                        if (status == 401 && cloud.userId != null) {
                            cloud.clear();
                            account();
                            message("Entre novamente para acessar a nuvem.");
                        } else if (status == 401) message("Confira o e-mail e a senha.");
                        else if (status == 404) message("O recurso solicitado não está disponível.");
                        else if (status == 409) message("Essa conta ou registro já existe.");
                        else if (status == 429) message("Muitas tentativas. Aguarde e tente novamente.");
                        else message("O serviço de nuvem não concluiu a operação.");
                    } else if (failure.getMessage() != null && failure.getMessage().startsWith("Este código")) {
                        message(failure.getMessage());
                    } else message("Não foi possível concluir. Tente novamente.");
                });
            }
        });
    }
}
