package org.flowgrid.android;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flowgrid.R;
import org.flowgrid.android.api.AndroidApiSetup;
import org.flowgrid.android.api.ImageImpl;
import org.flowgrid.android.api.SoundImpl;
import org.flowgrid.android.classifier.ClassifierFragment;
import org.flowgrid.android.classifier.VirtualOperationFragment;
import org.flowgrid.android.data.ArrayFragment;
import org.flowgrid.android.data.DataDialog;
import org.flowgrid.android.data.InstanceFragment;
import org.flowgrid.android.module.ModuleFragment;
import org.flowgrid.android.operation.EditOperationFragment;
import org.flowgrid.android.operation.RunOperationFragment;
import org.flowgrid.android.property.PropertyFragment;
import org.flowgrid.android.type.TypeFilter;
import org.flowgrid.android.type.TypeMenu;
import org.flowgrid.android.widget.ContextMenu;
import org.flowgrid.model.ArrayType;
import org.flowgrid.model.Artifact;
import org.flowgrid.model.Callback;
import org.flowgrid.model.Classifier;
import org.flowgrid.model.Container;
import org.flowgrid.model.CustomOperation;
import org.flowgrid.model.Image;
import org.flowgrid.model.Member;
import org.flowgrid.model.Property;
import org.flowgrid.model.StructuredData;
import org.flowgrid.model.Type;
import org.flowgrid.model.TypeAndValue;
import org.flowgrid.model.Types;
import org.flowgrid.model.VirtualOperation;
import org.flowgrid.model.Model;
import org.flowgrid.model.Module;
import org.flowgrid.model.Operation;
import org.flowgrid.model.Platform;
import org.flowgrid.model.ResourceFile;
import org.flowgrid.model.Sound;
import org.flowgrid.model.hutn.HutnObject;
import org.kobjects.filesystem.api.Filesystem;
import org.kobjects.filesystem.api.IOCallback;
import org.kobjects.filesystem.drive.DriveFs;
import org.kobjects.filesystem.local.LocalFs;
import org.kobjects.filesystem.sync.Syncer;
import org.shokai.firmata.ArduinoFirmata;

import com.badlogic.audio.io.WaveDecoder;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.http.javanet .NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.widget.DrawerLayout;
import android.text.Spannable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements Platform, ContextMenu.HelpProvider {
  protected static final int RESOLVE_CONNECTION_REQUEST_CODE_START = 9000;  // Reserve some
  protected static final int RESOLVE_CONNECTION_REQUEST_CODE_END = 9999;  // Reserve some

  enum SyncState {
    NONE, SYNCING, FAILED
  }

  private static final String TAG = "MainActivity";
  private File storageRootDir;
  private File metadataRootDir;
  private Model model;
  private HutnObject editBuffer;
  public Filesystem assetsFilesystem;
  private Filesystem metadataFilesystem;
  private Filesystem storageFilesystem;
  private Settings settings;
  private boolean destroyed;
  private DrawerLayout drawer;
  private ArrayList<Syncer> connections = new ArrayList<Syncer>();
  private ArrayList<Syncer> pendingSyncs = new ArrayList<Syncer>();
  private boolean runMode;
  private ArduinoFirmata arduinoFirmata;
  private int actionBarIconId = R.drawable.ic_menu_white_24dp;
  private SyncState syncState = SyncState.NONE;
  private LinkedHashMap<String, String> documentation = new LinkedHashMap<>();

  public Callback<Model> platformApiSetup() {
    return new AndroidApiSetup(this);
  }

  public HutnObject editBuffer() {
    return editBuffer;
  }

  public void error(final String message, final Exception e) {
    if (destroyed) {
      Log.d(TAG, "Error encountered after onDestroy(): " + message, e);
    } else {
      log(message, e);
      runOnUiThread(new Runnable() {
        @Override
        public void run() {
          AlertDialog.Builder alert = new AlertDialog.Builder(MainActivity.this);
          alert.setTitle("Error");
          if (message != null) {
            alert.setMessage(message);
          } else if (e != null) {
            alert.setMessage(e.toString());
          }
          alert.setPositiveButton("Ignore", null);
          alert.setNegativeButton("Terminate", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
              finish();
            }
          });
          Dialogs.showWithoutKeyboard(alert);
        }
      });
     
    }
  }
  
  public void finishFragment(Fragment fragment) {
    getFragmentManager().popBackStack();
  }

  public Image image(InputStream is) throws IOException {
    BufferedInputStream bis = new BufferedInputStream(is);
    Bitmap bitmap = BitmapFactory.decodeStream(bis);
    bis.close();
    is.close();
    return new ImageImpl(bitmap);
  }


  @Override
  public Filesystem metadataFileSystem() {
    return metadataFilesystem;
  }

  public Model model() {
    return model;
  }
  
  
  @SuppressLint("NewApi")
  private void updateIcon() {
    ActionBar actionBar = getActionBar();
    int iconId = actionBarIconId;
    if (iconId == R.drawable.ic_menu_white_24dp && syncState != SyncState.NONE) {
      iconId = syncState == SyncState.FAILED ? R.drawable.ic_cloud_off_white_24dp : R.drawable.ic_cloud_download_white_24dp;
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
      // Is this all needed?
      actionBar.setDisplayHomeAsUpEnabled(true);
      actionBar.setDisplayOptions(0, ActionBar.DISPLAY_SHOW_HOME);
      actionBar.setHomeAsUpIndicator(iconId);
    } else {
      actionBar.setIcon(iconId);
    }
  }
  public void showActionBar(Spannable title, String subtitle, int iconId) {
    ActionBar actionBar = getActionBar();
    actionBarIconId = iconId;
    updateIcon();
    actionBar.setTitle(title);
    actionBar.setSubtitle(subtitle);
    actionBar.setHomeButtonEnabled(true);
    actionBar.show();
  }
  
  public void hideActionBar() {
    getActionBar().hide();
  }

  protected void onCreate(final Bundle savedState) {
    super.onCreate(null);  // Prevent Android from restoring Fragments without the platform being available etc.
    settings = new Settings(this);

    setContentView(R.layout.root);
    drawer = ((DrawerLayout) findViewById(R.id.drawer_layout));
    registerDrawerHandlers();

    log("Setting up file systems", null);

    metadataRootDir = new File(getExternalFilesDir(null), "metadata");
    metadataFilesystem = new LocalFs(metadataRootDir);
    storageRootDir = new File(getExternalFilesDir(null), "flowgrid");
    storageFilesystem = new LocalFs(storageRootDir);

    log("Setting up the model and launching the module browser.");

    model = new Model(MainActivity.this);
   /* FragmentTransaction transaction = getFragmentManager().beginTransaction();
    transaction.add(R.id.rootContainer, new ModuleFragment(), null);
    transaction.commitAllowingStateLoss(); */

    loadDocumentation();
    log("FlowGrid operational. Starting IOIO and FS connections.");
    
    startStorageConnections();
    
    Bundle b = getIntent().getExtras();
    if (b != null && b.getString("run") != null) {
      runMode = true;
      openOperation((CustomOperation) model.artifact(b.getString("run")), false);
    } else {
      openModule(model.rootModule);
    }
  }


  private void loadDocumentation() {
    try {
      loadDocumentation("documentation.md");
      loadDocumentation("ui.md");
      loadDocumentation("api.md");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }


  private void loadDocumentation(String name) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open(name), "UTF-8"));
    String title = null;
    StringBuilder body = new StringBuilder();
    LinkedHashMap<String,String> result = new LinkedHashMap<>();
    while (true) {
      String line = reader.readLine();
      if (line == null || line.startsWith("#")) {
        if (title != null) {
          int pos = 0;
          while (pos < body.length() - 1) {
            if (body.charAt(pos) == '\n') {
              if (body.charAt(pos + 1) == '\n') {
                pos += 2;
              } else {
                body.setCharAt(pos, ' ');
              }
            } else {
              pos++;
            }
          }

          String text = body.toString().trim();
          result.put(title, text);
          Artifact artifact = model.artifact(title);
          if (artifact != null) {
            artifact.setDocumentation(text);
          }
        }
        if (line == null) {
          break;
        }
        int cut = 1;
        while (line.charAt(cut) == '#') {
          cut++;
        }
        title = line.substring(cut).trim();
        body.setLength(0);
      } else {
        body.append(line);
        body.append('\n');
      }
    }
  }


  private void startStorageConnections() {
    if (settings.storageConnections().size() > 0) {
      syncState = SyncState.SYNCING;
      updateIcon();
      new Thread(new Runnable() {
        public void run() {
          HutnObject storageJson = settings.storageConnections();
          for (Map.Entry<String, Object> entry : storageJson.entrySet()) {
            System.out.println("ConnectToDrive:" + entry);
            connectToDrive(entry.getKey(), (HutnObject) entry.getValue());
          }
        }
      }).start();
    }
  }

  private void registerDrawerHandlers() {
    ((TextView) findViewById(R.id.about)).setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        drawer.closeDrawers();
        AboutDialog.show(MainActivity.this);
      }
    });
    
    ((TextView) findViewById(R.id.show_logs)).setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        drawer.closeDrawers();
        drawer.openDrawer(Gravity.END);
      }
    });

    ((TextView) findViewById(R.id.reset_metadata_button)).setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        reboot(Settings.BootCommand.CLEAR_METATDATA, null);
      }
    });
  }

  private void connectionError(Syncer syncer, Exception e) {
    error("Error connecting module '" + syncer.localRoot() + "' to drive ", e);
  }

  private void connectToDrive(String modulePath, HutnObject connectionJson) {
    log("Connecting '" + modulePath + "' to Google Drive");
    GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
        this, Collections.singleton(DriveScopes.DRIVE));
    String username = connectionJson.getString("username", "");
    if (username.indexOf('@') == -1) {
      username += "@gmail.com";
    }
    credential.setSelectedAccountName(username);

    Drive service = new com.google.api.services.drive.Drive.Builder(
        new NetHttpTransport(), JacksonFactory.getDefaultInstance(), credential)
      .setApplicationName("org.flowgrid").build();
    DriveFs driveFs = new DriveFs(service, connectionJson.getString("remotePath", ""));
    driveFs.mapFileExtension(Container.SIGNATURE_CACHE_FILE_EXTENSION, "text/plain");
    driveFs.mapFileExtension(Module.MODULE_FILE_EXTENSION, "text/plain");
    driveFs.mapFileExtension(Classifier.CLASS_FILE_EXTENSION, "text/plain");
    driveFs.mapFileExtension(Classifier.INTERFACE_FILE_EXTENSION, "text/plain");
    driveFs.mapFileExtension(CustomOperation.FILE_EXTENSION, "text/plain");
    
    final Syncer syncer = new Syncer(storageFilesystem, modulePath, driveFs, "");
    syncer.setStatusListener(this);
    connections.add(syncer);
    
    // Force authorization
    try {
      driveFs.list("/");
      connectedToDrive(syncer);
    } catch (UserRecoverableAuthIOException e) {
      startActivityForResult(((UserRecoverableAuthIOException) e).getIntent(), 
          RESOLVE_CONNECTION_REQUEST_CODE_START + connections.size() - 1);
    } catch (IOException e) {
      connectionError(syncer, e);
    }
  }

  private void syncFinished(boolean ok) {
    syncState = ok ? SyncState.NONE : SyncState.FAILED;
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        updateIcon();
        Fragment currentFragment = getFragmentManager().findFragmentById(R.id.rootContainer);
        if (currentFragment instanceof PlatformFragment) {
          ((PlatformFragment) currentFragment).refresh();
        }
      }
    });
  }
  
  private void connectedToDrive(final Syncer syncer) {
    log("Connected '" + syncer.localRoot() + "' to drive.");
    syncer.init();
    log("FS Synchronization initialized for '" + syncer.localRoot() + "'");
    boolean startThread;
    synchronized(pendingSyncs) {
      pendingSyncs.add(syncer);
      startThread = pendingSyncs.size() == 1;
    }
    if (startThread) {
      new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            while (true) {
              Syncer syncer;
              synchronized (pendingSyncs) {
                if (pendingSyncs.size() == 0) {
                  break;
                }
                syncer = pendingSyncs.get(0);
              }
              log("Starting FS background synchronization for '" + syncer.localRoot() + "'");
              try {
                syncer.sync();
              } catch (IOException e) {
                connectionError(syncer, e);
              }
              synchronized (pendingSyncs) {
                pendingSyncs.remove(0);
              }
              log("Finished FS background synchronization for '" + syncer.localRoot() + "'");
            }
            synchronized (pendingSyncs) {
              log("All FS background synchronizations finished. Starting metadata background synchronization");
              model.rootModule.syncAll();
              log("Background metadata synchronization finished.");
              syncFinished(true);
            }
          } catch (Exception e) {
            error("Sync error", e);
          }
        }
      }).start();
    }
  }

  public String documentation(String title) {
    String result = documentation.get(title);
    if (result == null) {
      return "(Documentation for '" + title + "' not found)";
    }
    return result;
  }

  @Override
  public String getHelp(String label) {
    return documentation.get(label.endsWith("…") ? label.substring(0, label.length() - 1) : label);
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    super.onActivityResult(requestCode, resultCode, intent);
    if (requestCode >= RESOLVE_CONNECTION_REQUEST_CODE_START &&
        requestCode < RESOLVE_CONNECTION_REQUEST_CODE_END) {
      Syncer syncer = connections.get(requestCode - RESOLVE_CONNECTION_REQUEST_CODE_START);
      if (resultCode == Activity.RESULT_OK) {
        connectedToDrive(syncer);
      } else {
        connectionError(syncer, new IOException("Resolving connection request failed."));
      }
    }
  }

  @Override
  public void onBackPressed() {
    if (drawer != null && drawer.isDrawerOpen(Gravity.START)) {
      drawer.closeDrawers();
    } else {
      super.onBackPressed();
    }
  }
  
  @Override
  public void onDestroy() {
    destroyed = true;
    super.onDestroy();
  }


  public void onCreateUi() {
  }

  public void openClassifier(final Classifier classifier) {
    final String tag = "edit:" + classifier.qualifiedName();
    if (!showFragmentForTag(tag)) {
      final Fragment fragment = new ClassifierFragment();
      showFragment(fragment, tag);
    }
  }

  public void openArtifact(Artifact artifact) {
    if (artifact instanceof Module) {
      openModule((Module) artifact);
    } else if (artifact instanceof Operation) {
      openOperation((Operation) artifact);
    } else if (artifact instanceof ResourceFile) {
      openResourceFile((ResourceFile) artifact);
    } else if (artifact instanceof Classifier) {
      openClassifier((Classifier) artifact);
    } else if (artifact instanceof Property) {
      openProperty((Property) artifact);
    } else {
      error("Can't open artifact " + artifact + " type " + artifact.getClass(), null);
    }
  }
  
  public void openData(Member member, boolean edit, String... path) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < path.length; i++) {
      if (i != 0) {
        sb.append("/");
      }
      sb.append(path[i]);
    }
    String tag = (edit ? "edit:" : "view:") + member.qualifiedName() + ":" + sb.toString();
    if (!showFragmentForTag(tag)) {
      StructuredData parent = member.structuredData(path);
      Object data = parent.get(path[path.length - 1]);
      Fragment fragment = data instanceof List ? new ArrayFragment() : new InstanceFragment();
      showFragment(fragment, tag);
    }
  }
  
  public void openModule(Module module) {
    String tag = "edit:" + module.qualifiedName();
    if (!showFragmentForTag(tag)) {
      showFragment(new ModuleFragment(), tag);
    }
  }

  public void openOperation(Operation operation) {
    if (operation instanceof CustomOperation) {
      openOperation((CustomOperation) operation, true);
    } else if (operation instanceof VirtualOperation) {
      openOperation((VirtualOperation) operation);
    } else {
      throw new RuntimeException("Can't open " + operation.getClass());
    }
  }
  
  public void openOperation(VirtualOperation operation) {
    String tag = "edit:" + operation.qualifiedName();
    if (!showFragmentForTag(tag)) {
      showFragment(new VirtualOperationFragment(), tag);
    }
  }
  
  public void openOperation(final CustomOperation operation, final boolean edit) {
    final String tag = (edit ? "edit:" : "run:") + operation.qualifiedName();
    if (!showFragmentForTag(tag)) {
      final Fragment fragment = edit ? new EditOperationFragment() :
        new RunOperationFragment();
      operation.ensureLoaded();
      showFragment(fragment, tag);
    }
  }

  public void openProperty(Property property) {
    String tag = "edit:" + property.qualifiedName();
    if (!showFragmentForTag(tag)) {
      showFragment(new PropertyFragment(), tag);
    }
  }

  
  private void openResourceFile(ResourceFile resourceFile) {
    String tag = "view:" + resourceFile.qualifiedName();
    if (!showFragmentForTag(tag)) {
      showFragment(new ResourceFileFragment(), tag);
    }
  }

  public void editStructuredDataValue(final Member owner, final String[] path, View anchor, final Callback<TypeAndValue> callback) {
    StructuredData data = owner.structuredData(path);
    String name = path[path.length - 1];
    Object value = data.get(name);
    Type type = data.type(name);
    if (value == null && Types.isAbstract(type)) {
      new TypeMenu(this, anchor, owner.module, data.type(name), TypeFilter.INSTANTIABLE, new Callback<Type>() {
        @Override
        public void run(Type type) {
          editStructuredDataValue(owner, path, type, callback);
        }
      }).show();
    } else {
      // If we already have an object, we use the object type, if it has one.
      if (!(value instanceof List)) {
        type = model.type(value);
      }
      editStructuredDataValue(owner, path, type, callback);
    }
  }

  private void editStructuredDataValue(Member owner, String[] path, final Type type, final Callback<TypeAndValue> callback) {
    if (Types.isPrimitive(type)) {
      DataDialog.show(this, owner, type, new Callback<Object>() {
        @Override
        public void run(Object value) {
          callback.run(new TypeAndValue(type, value));
        }
      }, path);
    } else {
      StructuredData data = owner.structuredData(path);
      String name = path[path.length - 1];
      Object value = data.get(name);
      if (value == null) {
        if (type instanceof ArrayType) {
          value = new ArrayList<Object>();
        } else if (type instanceof  Classifier) {
          value = ((Classifier) type).newInstance();
        } else {
          error("Can't create a value of type " + type, null);
          callback.cancel();
          return;
        }
        data.set(name, value);
      }
      // Lists don't have an implicit type
      callback.run(new TypeAndValue(type, value));

      openData(owner, true, path);
    }
  }
  
  public void reboot(Settings.BootCommand bootCommand, String path) {
    if (bootCommand != null) {
      settings.setBootCommand(bootCommand, path);
    }
    if (drawer.isDrawerOpen(Gravity.START)) {
      drawer.closeDrawer(Gravity.START);
      Handler handler = new Handler();
      handler.postDelayed(new Runnable() {
        @Override
        public void run() {
          reboot(null, null);
        }
      }, 100);
    } else {
      Intent intent = new Intent(this, BootActivity.class);
      startActivity(intent);
      finish();
    }
  }

  public void setEditBuffer(HutnObject json) {
    this.editBuffer = json;
  }

  public Settings settings() {
    return settings;
  }

  public boolean showFragmentForTag(String tag) {
    if (getFragmentManager().popBackStackImmediate(tag, 0)) {
      return true;
    }
    Fragment fragment = getFragmentManager().findFragmentByTag(tag);
    if (fragment == null) {
      return false; 
    }
    FragmentTransaction transaction = getFragmentManager().beginTransaction();

    Fragment current = getFragmentManager().findFragmentById(R.id.rootContainer);
    if (current == null) {
      transaction.add(R.id.rootContainer, fragment, tag);
    } else {
      transaction.remove(current);
      transaction.add(R.id.rootContainer, fragment, tag);
      transaction.addToBackStack(tag);
    }
    transaction.commitAllowingStateLoss();
    return true;
  }
  
  void showFragment(Fragment fragment, String name) {
    if (drawer.isDrawerOpen(Gravity.START)) {
      drawer.closeDrawers();
    }
    Bundle args = new Bundle();

    Log.d(TAG, "showFragment; name: '" + name + "'");

    String[] parts = name.split(":", -1);
    args.putString("action", parts[0]);
    args.putString("artifact", parts.length == 1 ? "" : parts[1]);
    if (parts.length > 2) {
      args.putString("data", parts[2]);
    }

    Fragment current = getFragmentManager().findFragmentById(R.id.rootContainer);
    FragmentTransaction transaction = getFragmentManager().beginTransaction();
    if (current == null) {
      fragment.setArguments(args);
      transaction.add(R.id.rootContainer, fragment, name);
    } else {
      if (current.getTag() != null) {
        args.putString("caller", current.getTag());
      }
      fragment.setArguments(args);
//    transaction.setCustomAnimations(android.R.anim.slide_in_left, android.R.anim.slide_out_right);
      transaction.remove(current);
      transaction.add(R.id.rootContainer, fragment, name);
      transaction.addToBackStack(name);
    }
    transaction.commitAllowingStateLoss();
  }
  
  @Override
  public Sound sound(InputStream in) throws IOException {
    WaveDecoder decoder = new WaveDecoder(in);
    SoundImpl sound = new SoundImpl(decoder.sampleRate());
    try {
      while(true) {
        float[] buf = new float[1024];
        int count = decoder.readSamples(buf);
        if (count <= 0) {
          break;
        }
        if (count == buf.length) {
          sound.addSamples(buf);
        } else {
          float[] trimmed = new float[count];
          System.arraycopy(buf, 0, trimmed, 0, count);
          sound.addSamples(trimmed);
        }
      }
    }
    finally {
      in.close();
    }
    return sound;
  }

  @Override
  public Filesystem storageFileSystem() {
    return storageFilesystem;
  }
  

  @Override
  public void log(final String message, Exception e) {
    Log.i(TAG, message, e);
    runOnUiThread(new Runnable() {
      public void run() {
        final ScrollView scrollView = (ScrollView) findViewById(R.id.right_drawer);
        ViewGroup group = ((ViewGroup) findViewById(R.id.logView));
        TextView tv = new TextView(MainActivity.this);
        tv.setText(message);
        group.addView(tv);
        scrollView.post(new Runnable() {
          @Override
          public void run() {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN);
          }
        });
      }
    });
  }

  @Override
  public IOCallback<Void> defaultIoCallback(final String message) {
    return new IOCallback<Void>() {
      @Override
      public void onSuccess(Void value) {
      }

      @Override
      public void onError(IOException e) {
        error(message + " -- " + e.getMessage(), e);
      }
      
    };
  }

  @Override
  public String platformId() {
    String id = android.os.Build.MANUFACTURER + " " + android.os.Build.PRODUCT + " " + android.os.Build.MODEL;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < id.length(); i++) {
      char c = id.charAt(i);
      if (c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
        sb.append(c);
      } else {
        sb.append('_');
      }
    }
    return sb.toString();
  }

  @Override
  public void info(final String message, Exception e) {
    log(message, e);
    runOnUiThread(new Runnable() {
      public void run() {
        Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
      }
    });    
  }

  @Override
  public void log(String message) {
    log(message, null);
  }

  public ArduinoFirmata arduinoFirmata() {
    if (arduinoFirmata == null) {
      arduinoFirmata = new ArduinoFirmata(this);
      try {
        arduinoFirmata.connect();
        info("ArduinoFirmata connected", null);
      } catch(Exception e) {
        error("ArduinoFirmata not connected", e);
      }
    }
    return arduinoFirmata;
  }
}
