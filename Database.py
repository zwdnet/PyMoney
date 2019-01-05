# -*- coding:utf-8 -*-

import sqlite3
import sys

# 数据库处理类 
class DataBase(object):
    def __init__(self, Name):
        self.DatabaseName = Name
        self.data = None
        try:
            self.con = sqlite3.connect(self.DatabaseName)
            self.cu = self.con.cursor()
        except sqlite3.Error as e:
            self.__logError(e.args[0])
            sys.exit(1)
        
    def Execute(self, QueryCommand):
        try:
            self.cu.execute(QueryCommand)
        except sqlite3.Error as e:
            self.__logError(e.args[0])
            sys.exit(1)

    def GetConnect(self):
        return self.con

    def GetCursor(self):
        return self.cu
        
    def GetResult(self):
        try:
            self.data = self.cu.fetchall()
        except sqlite3.Error as e:
            self.__logError(e.args[0])
            sys.exit(1)
        return self.data
        
    #将错误信息写到文件中
    def __logError(self, info): 
        print("数据库操作有错误，错误信息:%s\n请看日志文件。" % info)
        fp = open("DatabaseError.txt", "w")
        fp.write(info)
        fp.close()
        
    def __del__(self):
        self.con.commit()
        if self.cu:
             self.cu.close()
        if self.con:
             self.con.close()


if __name__ == "__main__":
    db = DataBase("moneyold.db")
    query = "select name from sqlite_master where type='table'"
    db.Execute(query)
    result = db.GetResult()
    print(result)
    